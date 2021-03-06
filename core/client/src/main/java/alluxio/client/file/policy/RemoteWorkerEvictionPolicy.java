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

package alluxio.client.file.policy;

import alluxio.client.block.BlockWorkerInfo;
import alluxio.wire.WorkerNetAddress;

import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A Policy that to return the remote worker with the most available bytes to which will be used for
 * evicting blocks.
 * The return remote worker must have available size which can hold the evicted blocks.
 * Otherwise, null is returned.
 *
 * Added by Chi-fan Chu
 */
@ThreadSafe
public final class RemoteWorkerEvictionPolicy {
  private final double mThreshold;
  private final boolean mGlobalLRUEnabled;

  /**
   * @param threshold remote worker memory utilization threshold
   * @param globalLRUEnabled if global LRU enabled
   */
  public RemoteWorkerEvictionPolicy(double threshold, boolean globalLRUEnabled) {
    mThreshold = threshold;
    mGlobalLRUEnabled = globalLRUEnabled;
  }

  /**
    * Get the remote worker with enough space for eviction.
    * Used by TieredBlockStore.evictBlockInternal.
    * Added by Chi-fan Chu
    * @param workerInfoList the info of the active workers
    * @param excludedAddress the worker with this address is excluded
    * @param toMoveSize the block size to be moved
    * @param forceMove force move even if remote worker is out of space
    * @return the address of the worker to write to
    */
  public WorkerNetAddress getRemoteWorker(List<BlockWorkerInfo> workerInfoList,
                                          WorkerNetAddress excludedAddress,
                                          long toMoveSize,
                                          boolean forceMove) {
    long mostAvailableBytes = -1;
    WorkerNetAddress result = null;
    long oldestBlockTime = Long.MAX_VALUE;
    WorkerNetAddress oldestBlockWorkerAddress = null;
    for (BlockWorkerInfo workerInfo : workerInfoList) {

      // get the worker with the oldest block
      if (workerInfo.getOldestBlockTime() < oldestBlockTime) {
        oldestBlockTime = workerInfo.getOldestBlockTime();
        oldestBlockWorkerAddress = workerInfo.getNetAddress();
      }

      if (!workerInfo.getNetAddress().equals(excludedAddress)) {
        // exclude local worker address
        if (((double) workerInfo.getUsedBytes() / workerInfo.getCapacityBytes() * 100)
                < mThreshold) {
          // satisfy memory threshold criteria
          if (!mGlobalLRUEnabled
                  || workerInfo.getCapacityBytes() - workerInfo.getUsedBytes() > toMoveSize) {
            // have enough free space
            if (workerInfo.getCapacityBytes() - workerInfo.getUsedBytes() > mostAvailableBytes) {
              // have enough capacity
              mostAvailableBytes = workerInfo.getCapacityBytes() - workerInfo.getUsedBytes();
              result = workerInfo.getNetAddress();
            }
          }
        }
      }
    }

    if (mGlobalLRUEnabled && result == null && forceMove) {
      if (!oldestBlockWorkerAddress.equals(excludedAddress)) {
        result = oldestBlockWorkerAddress;
      }
    }

    return result;
  }
}
