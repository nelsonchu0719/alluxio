/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the “License”). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block.evictor;

import alluxio.Constants;
import alluxio.client.block.BlockInStream;
import alluxio.client.block.BlockWorkerInfo;
import alluxio.client.block.RemoteBlockOutStream;
import alluxio.client.block.UnderStoreBlockInStream;
import alluxio.client.file.policy.RemoteWorkerEvictionPolicy;
import alluxio.master.block.BlockId;
import alluxio.util.ThreadFactoryUtils;
import alluxio.util.io.BufferUtils;
import alluxio.wire.FileInfo;
import alluxio.wire.WorkerNetAddress;
import alluxio.worker.AlluxioWorker;
import alluxio.worker.WorkerContext;
import alluxio.worker.block.BlockWorker;
import alluxio.worker.block.io.LocalFileBlockReader;
import alluxio.worker.block.meta.BlockMeta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread class to evict block to remote worker.
 */
public final class BlockMover {

  private static final int  BUF_LIMIT = 10000000;

  private static boolean    sRemoteEvictMemBlocksEnable = WorkerContext.getConf()
      .getBoolean(Constants.WORKER_EVICTOR_REMOTE_EVICT_IN_MEM_BLOCKS);

  private static boolean    sBackgroundEnable           = WorkerContext.getConf()
      .getBoolean(Constants.WORKER_EVICTOR_REMOTE_EVICT_PERSIST_BLOCKS_BACKGROUND);

  private static int        sRemoteWorkerMemThreshold   = WorkerContext.getConf()
      .getInt(Constants.WORKER_EVICTOR_REMOTE_EVICT_SPACE_THRESHOLD);

  // convert secs to millisecs
  private static long       sUnusedTimeThreshold = WorkerContext.getConf()
      .getLong(Constants.WORKER_EVICTOR_REMOTE_EVICT_UNUSED_TIME_THRESHOLD) * 1000;

  private static RemoteWorkerEvictionPolicy sRemoteEvictionPolicy =
      new RemoteWorkerEvictionPolicy(sRemoteWorkerMemThreshold);

  private static BlockMover sInstance;

  /** The executor service for BlockMover. */
  private final ExecutorService mBlockMoverExecutorService;

  private BlockMover() {
    // Setup the BlockRemoverExecutorService
    mBlockMoverExecutorService = Executors.newFixedThreadPool(4,
            ThreadFactoryUtils.build("block-worker-BlockMover-%d", false));
  }

  /**
   * @return singleton instance
   */
  public static BlockMover getInstance() {
    synchronized (BlockWorker.class) {
      if (sInstance == null) {
        sInstance = new BlockMover();
      }
      return sInstance;
    }
  }

  /**
   * @param forceMove force move even if remote worker is out of space
   * @return remote worker address for eviction
   */
  private static WorkerNetAddress getRemoteWorkerAddress(long toMoveSize, boolean forceMove) {
    // If remote worker eviction is enabled
    // Get workerInfoList from master
    WorkerNetAddress remoteWorkerAddress = null;
    try {
      List<BlockWorkerInfo> workerInfos =
              AlluxioWorker.get().getBlockWorker().getWorkerInfoList();
      remoteWorkerAddress = sRemoteEvictionPolicy.getRemoteWorker(
              workerInfos, AlluxioWorker.get().getNetAddress(), toMoveSize, forceMove);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return remoteWorkerAddress;
  }

  /**
   * Actions when evict a block.
   *
   * @param blockId  block id that is to be removed
   * @param blockMeta block meta in the alluxio storage
   * @throws IOException if I/O errors occur when removing this block file
   */
  public void move(long blockId, BlockMeta blockMeta) throws IOException {
    if (!sBackgroundEnable && !sRemoteEvictMemBlocksEnable) {
      // No remote worker eviction allowed.
      return;
    }

    // decide whether or not move the block to remote worker according to
    // last access time
    boolean forceMove =
            System.currentTimeMillis() - blockMeta.getLastAccessTime() <= sUnusedTimeThreshold;

    FileInfo fileInfo = AlluxioWorker.get().getBlockWorker().getFileInfoWithBlock(blockId);
    String   ufsPath       = fileInfo.getUfsPath();
    String   blockPath     = blockMeta.getPath();
    long     fileBlockSize = fileInfo.getBlockSizeBytes();
    long     toMoveSize    = blockMeta.getBlockSize();
    boolean  isPersisted   = fileInfo.isPersisted();

    if (isPersisted) {
      if (sBackgroundEnable) {
        // use background process to move persisted blocks from disk
        mBlockMoverExecutorService.submit(
                new BlockMoverBackground(blockId, ufsPath, fileBlockSize, toMoveSize, forceMove));
      }
    }

    if (sRemoteEvictMemBlocksEnable) {
      moveBlockFromAlluxioStorage(blockId, blockPath, fileBlockSize, toMoveSize, forceMove);
    }
  }

  /**
   * Move this block from this worker's alluxio storage to remote worker's alluxio storage.
   *
   * @param blockId block id
   * @param blockPath alluxio storage path associated with this block
   * @param fileBlockSize file block size
   * @param toMoveSize the size of the block to be moved
   */
  private void moveBlockFromAlluxioStorage(long blockId, String blockPath,
       long fileBlockSize, long toMoveSize, boolean forceMove) {
    WorkerNetAddress remoteAddress = getRemoteWorkerAddress(toMoveSize, forceMove);

    if (remoteAddress == null) {
      return;
    }

    // Read block from local worker with LocalBlockInStream
    // make a write request to new worker with NettyRemoteBlockWriter
    // Commit block at new worker by closing the RemoteBlockOutStream
    try (LocalFileBlockReader reader =
                 new LocalFileBlockReader(blockPath);
         RemoteBlockOutStream os =
                 new RemoteBlockOutStream(
                         blockId, fileBlockSize, remoteAddress, true)) {
      long pos = 0;
      byte[] bytes = new byte[BUF_LIMIT];
      try {
        while (pos < toMoveSize) {
          int len = (int) ((toMoveSize - pos) <= BUF_LIMIT
                  ? (toMoveSize - pos) : BUF_LIMIT);
          ByteBuffer buf = reader.read(pos, len);
          buf.get(bytes, 0, len);
          BufferUtils.cleanDirectBuffer(buf);

          os.write(bytes, 0, len);

          pos += len;
        }
      } catch (IOException e) {
        os.cancel();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Runnable class for moving block.
   */
  public static class BlockMoverBackground implements Runnable {
    private final long              mBlockId;
    private final String            mUfsPath;
    // the block size associated with this file,
    // not necessarily have to be the same as the block to be moved
    private final long              mFileBlockSize;
    private final long              mToMoveSize;
    private final boolean           mForceMove;

    /**
     * @param blockId block id
     * @param ufsPath ufs path associated with this block id
     * @param fileBlockSize file block size
     * @param toMoveSize data size to be evicted
     * @param forceMove force move even if remote worker is out of space
     */
    public BlockMoverBackground(long blockId, String ufsPath,
        long fileBlockSize, long toMoveSize, boolean forceMove) {
      mBlockId            = blockId;
      mUfsPath            = ufsPath;
      mFileBlockSize      = fileBlockSize;
      mToMoveSize         = toMoveSize;
      mForceMove          = forceMove;
    }

    @Override
    public void run() {
      WorkerNetAddress remoteAddress = getRemoteWorkerAddress(mToMoveSize, mForceMove);

      if (remoteAddress == null) {
        return;
      }

      long blockStart = BlockId.getSequenceNumber(mBlockId) * mFileBlockSize;
      try (BlockInStream          is =
                   new UnderStoreBlockInStream(blockStart, mFileBlockSize, mUfsPath);
           RemoteBlockOutStream   os =
                   new RemoteBlockOutStream(mBlockId, mFileBlockSize, remoteAddress, true)) {
        int  readLen;
        long remain = mToMoveSize;
        int  sendLen;
        byte[] bytes = new byte[BUF_LIMIT];
        try {
          while (remain > 0 && (readLen = is.read(bytes)) != -1) {
            sendLen = (int) (remain > readLen ? readLen : remain);
            os.write(bytes, 0, sendLen);
            remain -= sendLen;
          }
        } catch (IOException e) {
          os.cancel();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
