/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.fs.cluster;

import io.activej.async.function.AsyncSupplier;
import io.activej.async.function.AsyncSuppliers;
import io.activej.async.service.EventloopService;
import io.activej.common.api.WithInitializer;
import io.activej.eventloop.Eventloop;
import io.activej.fs.ActiveFs;
import io.activej.fs.exception.FsException;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.fs.cluster.ServerSelector.RENDEZVOUS_HASH_SHARDER;

public final class FsPartitions implements EventloopService, WithInitializer<FsPartitions> {
	private static final Logger logger = LoggerFactory.getLogger(FsPartitions.class);

	private final Map<Object, ActiveFs> alivePartitions = new HashMap<>();
	private final Map<Object, ActiveFs> alivePartitionsView = Collections.unmodifiableMap(alivePartitions);

	private final Map<Object, ActiveFs> deadPartitions = new HashMap<>();
	private final Map<Object, ActiveFs> deadPartitionsView = Collections.unmodifiableMap(deadPartitions);

	private final AsyncSupplier<Void> checkAllPartitions = AsyncSuppliers.reuse(this::doCheckAllPartitions);
	private final AsyncSupplier<Void> checkDeadPartitions = AsyncSuppliers.reuse(this::doCheckDeadPartitions);

	private final Map<Object, ActiveFs> partitions;
	private final Map<Object, ActiveFs> partitionsView;

	private final Eventloop eventloop;

	private ServerSelector serverSelector = RENDEZVOUS_HASH_SHARDER;

	private FsPartitions(Eventloop eventloop, Map<Object, ActiveFs> partitions) {
		this.eventloop = eventloop;
		this.partitions = partitions;
		this.alivePartitions.putAll(partitions);
		this.partitionsView = Collections.unmodifiableMap(partitions);
	}

	public static FsPartitions create(Eventloop eventloop) {
		return new FsPartitions(eventloop, new HashMap<>());
	}

	public static FsPartitions create(Eventloop eventloop, Map<Object, ActiveFs> partitions) {
		return new FsPartitions(eventloop, partitions);
	}

	public FsPartitions withPartition(Object id, ActiveFs partition) {
		this.partitions.put(id, partition);
		alivePartitions.put(id, partition);
		return this;
	}

	/**
	 * Sets the server selection strategy based on file name and alive partitions
	 */
	public FsPartitions withServerSelector(@NotNull ServerSelector serverSelector) {
		this.serverSelector = serverSelector;
		return this;
	}

	/**
	 * Returns an unmodifiable view of all partitions
	 */
	public Map<Object, ActiveFs> getPartitions() {
		return partitionsView;
	}

	/**
	 * Returns an unmodifiable view of alive partitions
	 */
	public Map<Object, ActiveFs> getAlivePartitions() {
		return alivePartitionsView;
	}

	/**
	 * Returns an unmodifiable view of dead partitions
	 */
	public Map<Object, ActiveFs> getDeadPartitions() {
		return deadPartitionsView;
	}

	/**
	 * Returns alive {@link ActiveFs} by given id
	 *
	 * @param partitionId id of {@link ActiveFs}
	 * @return alive {@link ActiveFs}
	 */
	@Nullable
	public ActiveFs get(Object partitionId) {
		return alivePartitions.get(partitionId);
	}

	/**
	 * Starts a check process, which pings all partitions and marks them as dead or alive accordingly
	 *
	 * @return promise of the check
	 */
	public Promise<Void> checkAllPartitions() {
		return checkAllPartitions.get()
				.whenComplete(toLogger(logger, "checkAllPartitions"));
	}

	/**
	 * Starts a check process, which pings all dead partitions to possibly mark them as alive.
	 * This is the preferred method as it does nothing when no partitions are marked as dead,
	 * and RemoteF operations themselves do mark nodes as dead on connection failures.
	 *
	 * @return promise of the check
	 */
	public Promise<Void> checkDeadPartitions() {
		return checkDeadPartitions.get()
				.whenComplete(toLogger(logger, "checkDeadPartitions"));
	}

	/**
	 * Mark partition as dead. It means that no operations will use it and it would not be given to the server selector.
	 * Next call to {@link #checkDeadPartitions()} or {@link #checkAllPartitions()} will ping this partition and possibly
	 * mark it as alive again.
	 *
	 * @param partitionId id of the partition to be marked
	 * @param e           optional exception for logging
	 * @return <code>true</code> if partition was alive and <code>false</code> otherwise
	 */
	public boolean markDead(Object partitionId, @Nullable Throwable e) {
		ActiveFs partition = alivePartitions.remove(partitionId);
		if (partition != null) {
			logger.warn("marking {} as dead ", partitionId, e);
			deadPartitions.put(partitionId, partition);
			return true;
		}
		return false;
	}

	public void markAlive(Object partitionId) {
		ActiveFs partition = deadPartitions.remove(partitionId);
		if (partition != null) {
			logger.info("Partition {} is alive again!", partitionId);
			alivePartitions.put(partitionId, partition);
		}
	}

	/**
	 * If partition has returned exception other than {@link FsException} that indicates that there were connection problems
	 * or that there were no response at all
	 */
	public void markIfDead(Object partitionId, Throwable e) {
		if (e.getClass() != FsException.class) {
			markDead(partitionId, e);
		}
	}

	public  <T> BiFunction<T, Throwable, Promise<T>> wrapDeath(Object partitionId) {
		return (res, e) -> {
			if (e == null) {
				return Promise.of(res);
			}
			markIfDead(partitionId, e);
			return Promise.ofException(new FsException(FsPartitions.class, "Node failed with exception", e));
		};
	}

	public List<Object> select(String filename) {
		return serverSelector.selectFrom(filename, alivePartitions.keySet());
	}

	@NotNull
	@Override
	public Eventloop getEventloop() {
		return eventloop;
	}

	public ServerSelector getServerSelector() {
		return serverSelector;
	}

	@NotNull
	@Override
	public  Promise<?> start() {
		return checkAllPartitions();
	}

	@NotNull
	@Override
	public Promise<?> stop() {
		return Promise.complete();
	}

	@Override
	public String toString() {
		return "FsPartitions{partitions=" + partitions + ", deadPartitions=" + deadPartitions + '}';
	}

	private Promise<Void> doCheckAllPartitions() {
		return Promises.all(
				partitions.entrySet().stream()
						.map(entry -> {
							Object id = entry.getKey();
							return entry.getValue()
									.ping()
									.mapEx(($, e) -> {
										if (e == null) {
											markAlive(id);
										} else {
											markDead(id, e);
										}
										return null;
									});
						}));
	}

	private Promise<Void> doCheckDeadPartitions() {
		return Promises.all(
				deadPartitions.entrySet().stream()
						.map(entry -> entry.getValue()
								.ping()
								.mapEx(($, e) -> {
									if (e == null) {
										markAlive(entry.getKey());
									}
									return null;
								})));
	}
}
