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

package io.activej.fs.tcp;

import io.activej.csp.binary.ByteBufsCodec;
import io.activej.csp.net.Messaging;
import io.activej.csp.net.MessagingWithBinaryStreaming;
import io.activej.eventloop.Eventloop;
import io.activej.fs.ActiveFs;
import io.activej.fs.exception.FsException;
import io.activej.fs.tcp.RemoteFsCommands.*;
import io.activej.fs.tcp.RemoteFsResponses.*;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.net.AbstractServer;
import io.activej.net.socket.tcp.AsyncTcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.jmx.PromiseStats;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.activej.async.util.LogUtils.Level.TRACE;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.fs.ActiveFs.BAD_RANGE;
import static io.activej.fs.ActiveFs.FILE_NOT_FOUND;
import static io.activej.fs.util.RemoteFsUtils.*;

/**
 * An implementation of {@link AbstractServer} for RemoteFs.
 * It exposes some given {@link ActiveFs} to the Internet in pair with {@link RemoteActiveFs}
 */
public final class ActiveFsServer extends AbstractServer<ActiveFsServer> {
	private static final ByteBufsCodec<FsCommand, FsResponse> SERIALIZER =
			nullTerminatedJson(RemoteFsCommands.CODEC, RemoteFsResponses.CODEC);

	public static final FsException NO_HANDLER_FOR_MESSAGE = new FsException(ActiveFsServer.class, "No handler for received message type");

	private final Map<Class<?>, MessagingHandler<FsCommand>> handlers = new HashMap<>();
	private final ActiveFs fs;

	// region JMX
	private final PromiseStats handleRequestPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats uploadPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats movePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats moveAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats listPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats pingPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deletePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deleteAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	// endregion

	private ActiveFsServer(Eventloop eventloop, ActiveFs fs) {
		super(eventloop);
		this.fs = fs;
		addHandlers();
	}

	public static ActiveFsServer create(Eventloop eventloop, ActiveFs fs) {
		return new ActiveFsServer(eventloop, fs);
	}

	public ActiveFs getFs() {
		return fs;
	}

	@Override
	protected void serve(AsyncTcpSocket socket, InetAddress remoteAddress) {
		MessagingWithBinaryStreaming<FsCommand, FsResponse> messaging =
				MessagingWithBinaryStreaming.create(socket, SERIALIZER);
		messaging.receive()
				.then(msg -> {
					MessagingHandler<FsCommand> handler = handlers.get(msg.getClass());
					if (handler == null) {
						logger.warn("received a message with no associated handler, type: {}", msg.getClass());
						return Promise.ofException(NO_HANDLER_FOR_MESSAGE);
					}
					return handler.onMessage(messaging, msg);
				})
				.whenComplete(handleRequestPromise.recordStats())
				.whenException(e -> {
					logger.warn("got an error while handling message : {}", this, e);
					messaging.send(new ServerError(ERROR_TO_ID.getOrDefault(e, 0)))
							.then(messaging::sendEndOfStream)
							.whenResult(messaging::close);
				});
	}

	private void addHandlers() {
		onMessage(Upload.class, (messaging, msg) -> {
			String name = msg.getName();
			Long size = msg.getSize();
			return (size == null ? fs.upload(name) : fs.upload(name, size))
					.map(uploader -> size == null ? uploader : uploader.transformWith(ofFixedSize(size)))
					.then(uploader -> messaging.send(new UploadAck())
							.then(() -> messaging.receiveBinaryStream().streamTo(uploader)))
					.then(() -> messaging.send(new UploadFinished()))
					.then(messaging::sendEndOfStream)
					.whenResult(messaging::close)
					.whenComplete(uploadPromise.recordStats())
					.whenComplete(toLogger(logger, TRACE, "receiving data", msg, this));
		});

		onMessage(Append.class, (messaging, msg) -> {
			String name = msg.getName();
			long offset = msg.getOffset();
			return fs.append(name, offset)
					.then(uploader -> messaging.send(new AppendAck())
							.then(() -> messaging.receiveBinaryStream().streamTo(uploader)))
					.then(() -> messaging.send(new AppendFinished()))
					.then(messaging::sendEndOfStream)
					.whenResult(messaging::close);
		});

		onMessage(Download.class, (messaging, msg) -> {
			String name = msg.getName();
			long offset = msg.getOffset();
			long limit = msg.getLimit();

			if (offset < 0 || limit < 0) {
				return Promise.ofException(BAD_RANGE);
			}
			return fs.info(name)
					.then(meta -> {
						if (meta == null) {
							return Promise.ofException(FILE_NOT_FOUND);
						}

						long fixedLimit = Math.max(0, Math.min(meta.getSize() - offset, limit));

						return fs.download(name, offset, fixedLimit)
								.then(supplier -> messaging.send(new DownloadSize(fixedLimit))
										.whenException(supplier::closeEx)
										.then(() -> supplier.streamTo(messaging.sendBinaryStream())))
								.whenComplete(toLogger(logger, "sending data", meta, offset, fixedLimit, this));
					})
					.whenComplete(downloadPromise.recordStats());
		});
		onMessage(Copy.class, simpleHandler(msg -> fs.copy(msg.getName(), msg.getTarget()), $ -> new CopyFinished(), copyPromise));
		onMessage(CopyAll.class, simpleHandler(msg -> fs.copyAll(msg.getSourceToTarget()), $ -> new CopyAllFinished(), copyAllPromise));
		onMessage(Move.class, simpleHandler(msg -> fs.move(msg.getName(), msg.getTarget()), $ -> new MoveFinished(), movePromise));
		onMessage(MoveAll.class, simpleHandler(msg -> fs.moveAll(msg.getSourceToTarget()), $ -> new MoveAllFinished(), moveAllPromise));
		onMessage(Delete.class, simpleHandler(msg -> fs.delete(msg.getName()), $ -> new DeleteFinished(), deletePromise));
		onMessage(DeleteAll.class, simpleHandler(msg -> fs.deleteAll(msg.getFilesToDelete()), $ -> new DeleteAllFinished(), deleteAllPromise));
		onMessage(List.class, simpleHandler(msg -> fs.list(msg.getGlob()), ListFinished::new, listPromise));
		onMessage(Info.class, simpleHandler(msg -> fs.info(msg.getName()), InfoFinished::new, infoPromise));
		onMessage(InfoAll.class, simpleHandler(msg -> fs.infoAll(msg.getNames()), InfoAllFinished::new, infoAllPromise));
		onMessage(Ping.class, simpleHandler(msg -> fs.ping(), $ -> new PingFinished(), pingPromise));
	}

	private <T extends FsCommand, R> MessagingHandler<T> simpleHandler(Function<T, Promise<R>> action, Function<R, FsResponse> response, PromiseStats stats) {
		return (messaging, msg) -> action.apply(msg)
				.then(res -> messaging.send(response.apply(res)))
				.then(messaging::sendEndOfStream)
				.whenComplete(stats.recordStats());
	}

	@FunctionalInterface
	private interface MessagingHandler<T extends FsCommand> {
		Promise<Void> onMessage(Messaging<FsCommand, FsResponse> messaging, T item);
	}

	@SuppressWarnings("unchecked")
	private <T extends FsCommand> void onMessage(Class<T> type, MessagingHandler<T> handler) {
		handlers.put(type, (MessagingHandler<FsCommand>) handler);
	}

	@Override
	public String toString() {
		return "ActiveFsServer(" + fs + ')';
	}

	// region JMX
	@JmxAttribute
	public PromiseStats getUploadPromise() {
		return uploadPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadPromise() {
		return downloadPromise;
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
	public PromiseStats getPingPromise() {
		return pingPromise;
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

	@JmxAttribute
	public PromiseStats getDeletePromise() {
		return deletePromise;
	}

	@JmxAttribute
	public PromiseStats getDeleteAllPromise() {
		return deleteAllPromise;
	}

	@JmxAttribute
	public PromiseStats getHandleRequestPromise() {
		return handleRequestPromise;
	}
	// endregion
}
