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

package alluxio.client.block;

import alluxio.client.ClientContext;
import alluxio.client.RemoteBlockWriter;
import alluxio.exception.AlluxioException;
import alluxio.exception.BlockAlreadyExistsException;
import alluxio.wire.WorkerNetAddress;

import java.io.IOException;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Provides a streaming API to write to an Alluxio block. This output stream will send the write
 * through an Alluxio worker which will then write the block to a file in Alluxio storage.
 */
@NotThreadSafe
public final class RemoteBlockOutStream extends BufferedBlockOutStream {
  private final RemoteBlockWriter mRemoteWriter;
  private final BlockWorkerClient mBlockWorkerClient;
  private       boolean           mIsEviction = false;
  // default is -1, meaning committed by client
  private       long              mLastAccessTime = -1;

  /**
   * Creates a new block output stream.
   *
   * @param blockId the block id
   * @param blockSize the block size
   * @throws IOException if I/O error occurs
   */
  public RemoteBlockOutStream(long blockId, long blockSize) throws IOException {
    super(blockId, blockSize);
    mRemoteWriter = RemoteBlockWriter.Factory.create(ClientContext.getConf());
    mBlockWorkerClient = mContext.acquireWorkerClient();
    try {
      mBlockWorkerClient.connect();
      mRemoteWriter.open(mBlockWorkerClient.getDataServerAddress(), mBlockId,
          mBlockWorkerClient.getSessionId());
    } catch (IOException e) {
      mContext.releaseWorkerClient(mBlockWorkerClient);
      throw e;
    }
  }

  /**
   * Creates a new block output stream for remote worker eviction.
   *
   * @param blockId the block id
   * @param blockSize the block size
   * @param address the address of the preferred worker
   * @param lastAccessTime last access time
   * @throws IOException if I/O error occurs
   */
  public RemoteBlockOutStream(long blockId, long blockSize, WorkerNetAddress address,
       long lastAccessTime)
          throws IOException {
    this(blockId, blockSize, address);
    mIsEviction = true;
    mRemoteWriter.setEviction(mIsEviction);
    mLastAccessTime = lastAccessTime;
  }

  /**
   * Creates a new block output stream on a specific address.
   *
   * @param blockId the block id
   * @param blockSize the block size
   * @param address the address of the preferred worker
   * @throws IOException if I/O error occurs
   */
  public RemoteBlockOutStream(long blockId, long blockSize, WorkerNetAddress address)
      throws IOException {
    super(blockId, blockSize);
    mRemoteWriter = RemoteBlockWriter.Factory.create(ClientContext.getConf());
    mBlockWorkerClient = mContext.acquireWorkerClient(address);
    try {
      mBlockWorkerClient.connect();
      mRemoteWriter.open(mBlockWorkerClient.getDataServerAddress(), mBlockId,
          mBlockWorkerClient.getSessionId());
    } catch (IOException e) {
      mContext.releaseWorkerClient(mBlockWorkerClient);
      throw e;
    }
  }

  @Override
  public void cancel() throws IOException {
    if (mClosed) {
      return;
    }
    mRemoteWriter.close();
    if (isBlockCreated()) {
      // cancel block only if the block was created (added by Chi-fan Chu)
      try {
        mBlockWorkerClient.cancelBlock(mBlockId);
      } catch (AlluxioException e) {
        throw new IOException(e);
      }
    }
    mContext.releaseWorkerClient(mBlockWorkerClient);
    mClosed = true;
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    flush();
    mRemoteWriter.close();
    if (mFlushedBytes > 0) {
      try {
        mBlockWorkerClient.cacheBlock(mBlockId, mLastAccessTime);
      } catch (AlluxioException e) {
        throw new IOException(e);
      }
      ClientContext.getClientMetrics().incBlocksWrittenRemote(1);
    } else {
      if (isBlockCreated()) {
        // cancel block only if the block was created (added by Chi-fan Chu)
        try {
          mBlockWorkerClient.cancelBlock(mBlockId);
        } catch (AlluxioException e) {
          throw new IOException(e);
        }
      }
    }
    mContext.releaseWorkerClient(mBlockWorkerClient);
    mClosed = true;
  }

  @Override
  public void flush() throws IOException {
    writeToRemoteBlock(mBuffer.array(), 0, mBuffer.position());
    mBuffer.clear();
  }

  @Override
  protected void unBufferedWrite(byte[] b, int off, int len) throws IOException {
    writeToRemoteBlock(b, off, len);
  }

  private void writeToRemoteBlock(byte[] b, int off, int len)
          throws IOException {
    try {
      mRemoteWriter.write(b, off, len);
    } catch (BlockAlreadyExistsException e) {
      // handled block creation failure (added by Chi-fan Chu)
      setBlockCreationFailed();
      throw new IOException(e);
    }
    mFlushedBytes += len;
    ClientContext.getClientMetrics().incBytesWrittenRemote(len);
  }
}
