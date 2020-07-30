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

import io.activej.async.process.AsyncCloseable;
import io.activej.async.service.EventloopService;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.api.WithInitializer;
import io.activej.common.collection.Try;
import io.activej.common.ref.RefBoolean;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.dsl.ChannelConsumerTransformer;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.jmx.EventloopJmxBeanEx;
import io.activej.fs.ActiveFs;
import io.activej.fs.FileMetadata;
import io.activej.fs.exception.FsException;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.jmx.PromiseStats;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.collection.CollectionUtils.transformIterator;
import static io.activej.csp.dsl.ChannelConsumerTransformer.identity;
import static io.activej.fs.util.RemoteFsUtils.ofFixedSize;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * An implementation of {@link ActiveFs} which operates on a map of other partitions as a cluster.
 * Contains some redundancy and fail-safety capabilities.
 */
public final class ClusterActiveFs implements ActiveFs, WithInitializer<ClusterActiveFs>, EventloopService, EventloopJmxBeanEx {
	private static final Logger logger = LoggerFactory.getLogger(ClusterActiveFs.class);

	private final FsPartitions partitions;

	/**
	 * Maximum allowed number of dead partitions, if there are more dead partitions than this number,
	 * the cluster is considered malformed.
	 */
	private int deadPartitionsThreshold = 0;

	/**
	 * Minimum number of uploads that have to succeed in order for upload to be considered successful.
	 */
	private int uploadTargetsMin = 1;

	/**
	 * Initial number of uploads that are initiated.
	 */
	private int uploadTargetsMax = 1;

	// region JMX
	private final PromiseStats uploadStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats uploadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats listPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats movePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats moveAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deletePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deleteAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	// endregion

	// region creators
	private ClusterActiveFs(FsPartitions partitions) {
		this.partitions = partitions;
	}

	public static ClusterActiveFs create(FsPartitions partitions) {
		return new ClusterActiveFs(partitions);
	}

	/**
	 * Sets the replication count that determines how many copies of the file should persist over the cluster.
	 */
	public ClusterActiveFs withReplicationCount(int replicationCount) {
		checkArgument(1 <= replicationCount && replicationCount <= partitions.getPartitions().size(),
				"Replication count cannot be less than one or greater than number of partitions");
		this.deadPartitionsThreshold = replicationCount - 1;
		this.uploadTargetsMin = replicationCount;
		this.uploadTargetsMax = replicationCount;
		return this;
	}

	/**
	 * Sets the replication count as well as number of upload targets that determines the number of server where file will be uploaded.
	 */
	public ClusterActiveFs withPersistenceOptions(int deadPartitionsThreshold, int uploadTargetsMin, int uploadTargetsMax) {
		checkArgument(0 <= deadPartitionsThreshold && deadPartitionsThreshold < partitions.getPartitions().size(),
				"Dead partitions threshold cannot be less than zero or greater than number of partitions");
		checkArgument(0 <= uploadTargetsMin,
				"Minimum number of upload targets should not be less than zero");
		checkArgument(0 < uploadTargetsMax && uploadTargetsMin <= uploadTargetsMax && uploadTargetsMax <= partitions.getPartitions().size(),
				"Maximum number of upload targets should be greater than zero, " +
						"should not be less than minimum number of upload targets and" +
						"should not exceed total number of partitions");
		this.deadPartitionsThreshold = deadPartitionsThreshold;
		this.uploadTargetsMin = uploadTargetsMin;
		this.uploadTargetsMax = uploadTargetsMax;
		return this;
	}
	// endregion

	// region getters
	@NotNull
	@Override
	public Eventloop getEventloop() {
		return partitions.getEventloop();
	}
	// endregion

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name) {
		return doUpload(name, fs -> fs.upload(name), identity(), uploadStartPromise, uploadFinishPromise);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name, long size) {
		return doUpload(name, fs -> fs.upload(name, size), ofFixedSize(size), uploadStartPromise, uploadFinishPromise);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(@NotNull String name, long offset) {
		return doUpload(name, fs -> fs.append(name, offset), identity(), appendStartPromise, appendFinishPromise);
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(@NotNull String name, long offset, long limit) {
		return broadcast(
				(id, fs) -> {
					logger.trace("downloading file {} from {}", name, id);
					return fs.download(name, offset, limit)
							.whenException(e -> logger.warn("Failed to connect to a server with key " + id + " to download file " + name, e))
							.map(supplier -> supplier
									.withEndOfStream(eos -> eos
											.thenEx(partitions.wrapDeath(id))));
				},
				AsyncCloseable::close)
				.then(suppliers -> {
					if (suppliers.isEmpty()) {
						return ofFailure("Could not download file '" + name + "' from any server");
					}
					ChannelByteCombiner combiner = ChannelByteCombiner.create();
					for (ChannelSupplier<ByteBuf> supplier : suppliers) {
						combiner.addInput().set(supplier);
					}
					return Promise.of(combiner.getOutput().getSupplier());
				})
				.whenComplete(downloadStartPromise.recordStats());
	}

	@Override
	public Promise<Void> copy(@NotNull String name, @NotNull String target) {
		return ActiveFs.super.copy(name, target)
				.whenComplete(copyPromise.recordStats());
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		return ActiveFs.super.copyAll(sourceToTarget)
				.whenComplete(copyAllPromise.recordStats());
	}

	@Override
	public Promise<Void> move(@NotNull String name, @NotNull String target) {
		return ActiveFs.super.move(name, target)
				.whenComplete(movePromise.recordStats());
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		return ActiveFs.super.moveAll(sourceToTarget)
				.whenComplete(moveAllPromise.recordStats());
	}

	@Override
	public Promise<Void> delete(@NotNull String name) {
		return broadcast(fs -> fs.delete(name))
				.whenComplete(deletePromise.recordStats())
				.toVoid();
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		return broadcast(fs -> fs.deleteAll(toDelete))
				.whenComplete(deleteAllPromise.recordStats())
				.toVoid();
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(@NotNull String glob) {
		return broadcast(fs -> fs.list(glob))
				.map(maps -> FileMetadata.flatten(maps.stream()))
				.whenComplete(listPromise.recordStats());
	}

	@Override
	public Promise<@Nullable FileMetadata> info(@NotNull String name) {
		return broadcast(fs -> fs.info(name))
				.map(meta -> meta.stream().max(FileMetadata.COMPARATOR).orElse(null))
				.whenComplete(infoPromise.recordStats());
	}

	@Override
	public Promise<Map<String, @NotNull FileMetadata>> infoAll(@NotNull Set<String> names) {
		if (names.isEmpty()) return Promise.of(emptyMap());

		return broadcast(fs -> fs.infoAll(names))
				.map(maps -> FileMetadata.flatten(maps.stream()))
				.whenComplete(infoAllPromise.recordStats());
	}

	@Override
	public Promise<Void> ping() {
		return partitions.checkAllPartitions()
				.then(this::checkNotDead);
	}

	@NotNull
	@Override
	public Promise<Void> start() {
		return ping();
	}

	@NotNull
	@Override
	public Promise<Void> stop() {
		return Promise.complete();
	}

	@Override
	public String toString() {
		return "ClusterActiveFs{partitions=" + partitions + '}';
	}

	private static <T> Promise<T> ofFailure(String message) {
		return Promise.ofException(new FsException(ClusterActiveFs.class, message));
	}

	private <T> Promise<T> checkStillNotDead(T value) {
		Map<Object, ActiveFs> deadPartitions = partitions.getDeadPartitions();
		if (deadPartitions.size() > deadPartitionsThreshold) {
			return ofFailure("There are more dead partitions than allowed(" +
					deadPartitions.size() + " dead, threshold is " + deadPartitionsThreshold + "), aborting");
		}
		return Promise.of(value);
	}

	private Promise<Void> checkNotDead() {
		return checkStillNotDead(null);
	}

	private Promise<ChannelConsumer<ByteBuf>> doUpload(
			String name,
			Function<ActiveFs, Promise<ChannelConsumer<ByteBuf>>> action,
			ChannelConsumerTransformer<ByteBuf, ChannelConsumer<ByteBuf>> transformer,
			PromiseStats startStats,
			PromiseStats finishStats) {
		return checkNotDead()
				.then(() -> collect(name, action))
				.then(containers -> {
					ChannelByteSplitter splitter = ChannelByteSplitter.create(uploadTargetsMin);
					for (Container<ChannelConsumer<ByteBuf>> container : containers) {
						splitter.addOutput().set(container.value);
					}

					if (logger.isTraceEnabled()) {
						logger.trace("uploading file {} to {}, {}", name, containers.stream()
								.map(container -> container.id.toString())
								.collect(joining(", ", "[", "]")), this);
					}

					return Promise.of(splitter.getInput().getConsumer()
							.transformWith(transformer))
							.whenComplete(finishStats.recordStats());
				})
				.whenComplete(startStats.recordStats());
	}

	private Promise<List<Container<ChannelConsumer<ByteBuf>>>> collect(
			String name,
			Function<ActiveFs, Promise<ChannelConsumer<ByteBuf>>> action
	) {
		Iterator<Object> idIterator = partitions.select(name).iterator();
		Set<ChannelConsumer<ByteBuf>> consumers = new HashSet<>();
		RefBoolean failed = new RefBoolean(false);
		return Promises.toList(
				Stream.generate(() ->
						Promises.firstSuccessful(transformIterator(idIterator,
								id -> call(id, action)
										.whenResult(consumer -> {
											if (failed.get()) {
												consumer.close();
											} else {
												consumers.add(consumer);
											}
										})
										.map(consumer -> new Container<>(id,
												consumer.withAcknowledgement(ack ->
														ack.thenEx(partitions.wrapDeath(id))))))))
						.limit(uploadTargetsMax))
				.thenEx((containers, e) -> {
					if (e != null) {
						consumers.forEach(AsyncCloseable::close);
						failed.set(true);
						return ofFailure("Didn't connect to enough partitions to upload '" + name + '\'');
					}
					return Promise.of(containers);
				});
	}

	private <T> Promise<T> call(Object id, Function<ActiveFs, Promise<T>> action) {
		return call(id, ($, fs) -> action.apply(fs));
	}

	private <T> Promise<T> call(Object id, BiFunction<Object, ActiveFs, Promise<T>> action) {
		ActiveFs fs = partitions.get(id);
		if (fs == null) {  // marked as dead already by somebody
			return Promise.ofException(new FsException(ClusterActiveFs.class, "Partition '" + id + "' is not alive"));
		}
		return action.apply(id, fs)
				.thenEx(partitions.wrapDeath(id));
	}

	private <T> Promise<List<T>> broadcast(BiFunction<Object, ActiveFs, Promise<T>> action, Consumer<T> cleanup) {
		return checkNotDead()
				.then(() -> Promise.<List<Try<T>>>ofCallback(cb ->
						Promises.toList(partitions.getAlivePartitions().entrySet().stream()
								.map(entry -> action.apply(entry.getKey(), entry.getValue())
										.thenEx(partitions.wrapDeath(entry.getKey()))
										.whenResult(result -> {
											if (cb.isComplete()) {
												cleanup.accept(result);
											}
										})
										.toTry()
										.then(aTry -> checkStillNotDead(aTry)
												.whenException(e -> aTry.ifSuccess(cleanup)))))
								.whenComplete(cb)))
				.map(tries -> tries.stream()
						.filter(Try::isSuccess)
						.map(Try::get)
						.collect(toList()));
	}

	private <T> Promise<List<T>> broadcast(Function<ActiveFs, Promise<T>> action) {
		return broadcast(($, fs) -> action.apply(fs), $ -> {});
	}

	private static class Container<T> {
		final Object id;
		final T value;

		Container(Object id, T value) {
			this.id = id;
			this.value = value;
		}
	}

	// region JMX
	@JmxAttribute
	public int getDeadPartitionsThreshold() {
		return deadPartitionsThreshold;
	}

	@JmxAttribute
	public int getUploadTargetsMin() {
		return uploadTargetsMin;
	}

	@JmxAttribute
	public int getUploadTargetsMax() {
		return uploadTargetsMax;
	}

	@JmxOperation
	public void setReplicationCount(int replicationCount) {
		withReplicationCount(replicationCount);
	}

	@JmxAttribute
	public void setDeadPartitionsThreshold(int deadPartitionsThreshold) {
		withPersistenceOptions(deadPartitionsThreshold, uploadTargetsMin, uploadTargetsMax);
	}

	@JmxAttribute
	public void setUploadTargetsMin(int uploadTargetsMin) {
		withPersistenceOptions(deadPartitionsThreshold, uploadTargetsMin, uploadTargetsMax);
	}

	@JmxAttribute
	public void setUploadTargetsMax(int uploadTargetsMax) {
		withPersistenceOptions(deadPartitionsThreshold, uploadTargetsMin, uploadTargetsMax);
	}

	@JmxAttribute
	public int getAlivePartitionCount() {
		return partitions.getAlivePartitions().size();
	}

	@JmxAttribute
	public int getDeadPartitionCount() {
		return partitions.getDeadPartitions().size();
	}

	@JmxAttribute
	public String[] getAlivePartitions() {
		return partitions.getAlivePartitions().keySet().stream()
				.map(Object::toString)
				.toArray(String[]::new);
	}

	@JmxAttribute
	public String[] getDeadPartitions() {
		return partitions.getDeadPartitions().keySet().stream()
				.map(Object::toString)
				.toArray(String[]::new);
	}

	@JmxAttribute
	public PromiseStats getUploadStartPromise() {
		return uploadStartPromise;
	}

	@JmxAttribute
	public PromiseStats getUploadFinishPromise() {
		return uploadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendStartPromise() {
		return appendStartPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendFinishPromise() {
		return appendFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadStartPromise() {
		return downloadStartPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadFinishPromise() {
		return downloadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getListPromise() {
		return listPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoPromise() {
		return infoPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoAllPromise() {
		return infoAllPromise;
	}

	@JmxAttribute
	public PromiseStats getDeletePromise() {
		return deletePromise;
	}

	@JmxAttribute
	public PromiseStats getDeleteAllPromise() {
		return deleteAllPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyPromise() {
		return copyPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyAllPromise() {
		return copyAllPromise;
	}

	@JmxAttribute
	public PromiseStats getMovePromise() {
		return movePromise;
	}

	@JmxAttribute
	public PromiseStats getMoveAllPromise() {
		return moveAllPromise;
	}
	// endregion
}
