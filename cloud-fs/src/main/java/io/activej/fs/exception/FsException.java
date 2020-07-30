package io.activej.fs.exception;

import io.activej.common.exception.StacklessException;
import org.jetbrains.annotations.NotNull;

public final class FsException extends StacklessException {
	public FsException() {
		super();
	}

	public FsException(@NotNull String message) {
		super(message);
	}

	public FsException(@NotNull String message, @NotNull Throwable cause) {
		super(message, cause);
	}

	public FsException(@NotNull Throwable cause) {
		super(cause);
	}

	public FsException(@NotNull Class<?> component, @NotNull String message) {
		super(component, message);
	}

	public FsException(@NotNull Class<?> component, @NotNull String message, @NotNull Throwable cause) {
		super(component, message, cause);
	}
}
