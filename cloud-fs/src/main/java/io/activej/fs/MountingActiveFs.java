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

package io.activej.fs;

import io.activej.async.function.AsyncSupplier;
import io.activej.bytebuf.ByteBuf;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

final class MountingActiveFs implements ActiveFs {
	private final ActiveFs root;
	private final Map<String, ActiveFs> mounts;

	MountingActiveFs(ActiveFs root, Map<String, ActiveFs> mounts) {
		this.root = root;
		this.mounts = mounts;
	}

	private ActiveFs findMount(String filename) {
		int idx = filename.lastIndexOf('/');
		while (idx != -1) {
			String path = filename.substring(0, idx);
			ActiveFs mount = mounts.get(path);
			if (mount != null) {
				return mount;
			}
			idx = filename.lastIndexOf('/', idx - 1);
		}
		return root;
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name) {
		return findMount(name).upload(name);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(@NotNull String name, long offset) {
		return findMount(name).append(name, offset);
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(@NotNull String name, long offset, long limit) {
		return findMount(name).download(name, offset, limit);
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(@NotNull String glob) {
		return Promises.toList(Stream.concat(Stream.of(root), mounts.values().stream()).map(f -> f.list(glob)))
				.map(listOfMaps -> FileMetadata.flatten(listOfMaps.stream()));
	}

	@Override
	public Promise<@Nullable FileMetadata> info(@NotNull String name) {
		return findMount(name).info(name);
	}

	@Override
	public Promise<Map<String, @NotNull FileMetadata>> infoAll(@NotNull Set<String> names) {
		Map<String, @NotNull FileMetadata> result = new HashMap<>();
		return Promises.all(names.stream()
				.collect(groupingBy(this::findMount, toSet()))
				.entrySet().stream()
				.map(entry -> entry.getKey()
						.infoAll(entry.getValue())
						.whenResult(result::putAll)))
				.map($ -> result);
	}

	@Override
	public Promise<Void> copy(@NotNull String name, @NotNull String target) {
		return transfer(name, target, (s, t) -> fs -> fs.copy(s, t), false);
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		return transfer(sourceToTarget, ActiveFs::copyAll, false);
	}

	@Override
	public Promise<Void> move(@NotNull String name, @NotNull String target) {
		return transfer(name, target, (s, t) -> fs -> fs.move(s, t), true);
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		return transfer(sourceToTarget, ActiveFs::moveAll, true);
	}

	@Override
	public Promise<Void> delete(@NotNull String name) {
		return findMount(name).delete(name);
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		return Promises.all(toDelete.stream()
				.collect(groupingBy(this::findMount, IdentityHashMap::new, toSet()))
				.entrySet().stream()
				.map(entry -> entry.getKey().deleteAll(entry.getValue())));
	}

	private Promise<Void> transfer(String source, String target, BiFunction<String, String, Function<ActiveFs, Promise<Void>>> action, boolean deleteSource) {
		ActiveFs first = findMount(source);
		ActiveFs second = findMount(target);
		if (first == second) {
			return action.apply(source, target).apply(first);
		}
		return first.download(source)
				.then(supplier -> supplier.streamTo(second.upload(target)))
				.then(() -> deleteSource ? first.delete(source) : Promise.complete());
	}

	private Promise<Void> transfer(Map<String, String> sourceToTarget, BiFunction<ActiveFs, Map<String, String>, Promise<Void>> action, boolean deleteSource) {
		List<AsyncSupplier<Void>> movePromises = new ArrayList<>();

		Map<ActiveFs, Map<String, String>> groupedBySameFs = new IdentityHashMap<>();

		for (Map.Entry<String, String> entry : sourceToTarget.entrySet()) {
			String source = entry.getKey();
			String target = entry.getValue();
			ActiveFs first = findMount(source);
			ActiveFs second = findMount(target);
			if (first == second) {
				groupedBySameFs
						.computeIfAbsent(first, $ -> new HashMap<>())
						.put(source, target);
			} else {
				movePromises.add(() -> first.download(source)
						.then(supplier -> supplier.streamTo(second.upload(target)))
						.then(() -> deleteSource ? first.delete(target) : Promise.complete()));
			}
		}
		for (Map.Entry<ActiveFs, Map<String, String>> entry : groupedBySameFs.entrySet()) {
			movePromises.add(() -> action.apply(entry.getKey(), entry.getValue()));
		}

		return Promises.all(Promises.asPromises(movePromises));
	}
}
