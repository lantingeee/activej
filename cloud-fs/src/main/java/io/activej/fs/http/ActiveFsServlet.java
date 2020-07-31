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

package io.activej.fs.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.UncheckedException;
import io.activej.common.tuple.Tuple1;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.fs.ActiveFs;
import io.activej.http.*;
import io.activej.http.MultipartParser.MultipartDataHandler;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import static io.activej.codec.json.JsonUtils.toJson;
import static io.activej.codec.json.JsonUtils.toJsonBuf;
import static io.activej.fs.ActiveFs.FILE_NOT_FOUND;
import static io.activej.fs.http.FsCommand.*;
import static io.activej.fs.util.Codecs.*;
import static io.activej.fs.util.RemoteFsUtils.ERROR_TO_ID;
import static io.activej.fs.util.RemoteFsUtils.parseBody;
import static io.activej.http.ContentTypes.JSON_UTF_8;
import static io.activej.http.ContentTypes.PLAIN_TEXT_UTF_8;
import static io.activej.http.HttpHeaderValue.ofContentType;
import static io.activej.http.HttpHeaders.ACCEPT_RANGES;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class ActiveFsServlet {
	private ActiveFsServlet() {
	}

	public static RoutingServlet create(ActiveFs fs) {
		return create(fs, true);
	}

	public static RoutingServlet create(ActiveFs fs, boolean inline) {
		return RoutingServlet.create()
				.map(POST, "/" + UPLOAD + "/*", request ->
						fs.upload(decodePath(request))
								.mapEx(acknowledgeUpload(request)))
				.map(POST, "/" + UPLOAD, request -> request.handleMultipart(MultipartDataHandler.file(fs::upload))
						.mapEx(errorHandler()))
				.map(POST, "/" + APPEND + "/*", request -> {
					long offset = getNumberParameterOr(request, "offset", 0);
					return fs.append(decodePath(request), offset)
							.mapEx(acknowledgeUpload(request));
				})
				.map(GET, "/" + DOWNLOAD + "/*", request -> {
					String name = decodePath(request);
					String rangeHeader = request.getHeader(HttpHeaders.RANGE);
					if (rangeHeader != null) {
						return rangeDownload(fs, inline, name, rangeHeader);
					}
					long offset = getNumberParameterOr(request, "offset", 0);
					long limit = getNumberParameterOr(request, "limit", Long.MAX_VALUE);
					return fs.download(name, offset, limit)
							.mapEx(errorHandler(supplier -> HttpResponse.ok200()
									.withHeader(ACCEPT_RANGES, "bytes")
									.withBodyStream(supplier)));
				})
				.map(GET, "/" + LIST, request -> {
					String glob = request.getQueryParameter("glob");
					glob = glob != null ? glob : "**";
					return (fs.list(glob))
							.mapEx(errorHandler(list ->
									HttpResponse.ok200()
											.withBody(toJson(FILE_META_MAP_CODEC, list).getBytes(UTF_8))
											.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))));
				})
				.map(GET, "/" + INFO + "/*", request ->
						fs.info(decodePath(request))
								.mapEx(errorHandler(meta ->
										HttpResponse.ok200()
												.withBody(toJson(FILE_META_CODEC_NULLABLE, meta).getBytes(UTF_8))
												.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8)))))
				.map(GET, "/" + INFO_ALL, request -> request.loadBody()
						.then(parseBody(STRINGS_SET_CODEC))
						.then(fs::infoAll)
						.mapEx(errorHandler(map ->
								HttpResponse.ok200()
										.withBody(toJson(FILE_META_MAP_CODEC, map).getBytes(UTF_8))
										.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8)))))
				.map(GET, "/" + PING, request -> fs.ping()
						.mapEx(errorHandler()))
				.map(POST, "/" + MOVE, request -> {
					String name = getQueryParameter(request, "name");
					String target = getQueryParameter(request, "target");
					return fs.move(name, target)
							.mapEx(errorHandler());
				})
				.map(POST, "/" + MOVE_ALL, request -> request.loadBody()
						.then(parseBody(SOURCE_TO_TARGET_CODEC))
						.then(fs::moveAll)
						.mapEx(errorHandler()))
				.map(POST, "/" + COPY, request -> {
					String name = getQueryParameter(request, "name");
					String target = getQueryParameter(request, "target");
					return fs.copy(name, target)
							.mapEx(errorHandler());
				})
				.map(POST, "/" + COPY_ALL, request -> request.loadBody()
						.then(parseBody(SOURCE_TO_TARGET_CODEC))
						.then(fs::copyAll)
						.mapEx(errorHandler()))
				.map(HttpMethod.DELETE, "/" + DELETE + "/*", request ->
						fs.delete(decodePath(request))
								.mapEx(errorHandler()))
				.map(POST, "/" + DELETE_ALL, request -> request.loadBody()
						.then(parseBody(STRINGS_SET_CODEC))
						.then(fs::deleteAll)
						.mapEx(errorHandler()));
	}

	@NotNull
	private static Promise<HttpResponse> rangeDownload(ActiveFs fs, boolean inline, String name, String rangeHeader) {
		return fs.info(name)
				.then(meta -> {
					if (meta == null) {
						return Promise.ofException(FILE_NOT_FOUND);
					}
					return HttpResponse.file(
							(offset, limit) -> fs.download(name, offset, limit),
							name,
							meta.getSize(),
							rangeHeader,
							inline);
				})
				.mapEx(errorHandler(Function.identity()));
	}

	private static String decodePath(HttpRequest request) {
		String value = UrlParser.urlDecode(request.getRelativePath());
		if (value == null) {
			throw new UncheckedException(HttpException.ofCode(400, "Path contains invalid UTF"));
		}
		return value;
	}

	private static String getQueryParameter(HttpRequest request, String parameterName) {
		String value = request.getQueryParameter(parameterName);
		if (value == null) {
			throw new UncheckedException(HttpException.ofCode(400, "No '" + parameterName + "' query parameter"));
		}
		return value;
	}

	private static long getNumberParameterOr(HttpRequest request, String parameterName, long defaultValue) {
		String value = request.getQueryParameter(parameterName);
		if (value == null) {
			return defaultValue;
		}
		try {
			long val = Long.parseLong(value);
			if (val < 0) {
				throw new NumberFormatException();
			}
			return val;
		} catch (NumberFormatException ignored) {
			throw new UncheckedException(HttpException.ofCode(400, "Invalid '" + parameterName + "' value"));
		}
	}

	private static HttpResponse getErrorResponse(Throwable e) {
		return HttpResponse.ofCode(500)
				.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))
				.withBody(toJsonBuf(ERROR_CODE_CODEC, new Tuple1<>(ERROR_TO_ID.getOrDefault(e, 0))));
	}

	private static <T> BiFunction<T, Throwable, HttpResponse> errorHandler() {
		return errorHandler($ -> HttpResponse.ok200().withHeader(CONTENT_TYPE, ofContentType(PLAIN_TEXT_UTF_8)));
	}

	private static <T> BiFunction<T, Throwable, HttpResponse> errorHandler(Function<T, HttpResponse> successful) {
		return (res, e) -> e == null ? successful.apply(res) : getErrorResponse(e);
	}

	private static BiFunction<ChannelConsumer<ByteBuf>, Throwable, HttpResponse> acknowledgeUpload(@NotNull HttpRequest request) {
		return errorHandler(consumer -> HttpResponse.ok200()
				.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))
				.withBodyStream(ChannelSupplier.ofPromise(request.getBodyStream()
						.streamTo(consumer)
						.mapEx(($, e) -> e == null ?
								UploadAcknowledgement.ok() :
								UploadAcknowledgement.ofErrorCode(ERROR_TO_ID.getOrDefault(e, 0)))
						.map(ack -> ChannelSupplier.of(toJsonBuf(UploadAcknowledgement.CODEC, ack))))));
	}

}
