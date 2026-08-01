package com.dragote.xcamera.shared.common.domain.result

/**
 * Standard success/error wrapper used across every repository/use-case boundary,
 * so ViewModels never have to deal with thrown exceptions from the data layer.
 */
sealed interface Result<out D, out E : DataError> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : DataError>(val error: E) : Result<Nothing, E>
}

inline fun <D, E : DataError, R> Result<D, E>.map(transform: (D) -> R): Result<R, E> =
    when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }

inline fun <D, E : DataError> Result<D, E>.onSuccess(action: (D) -> Unit): Result<D, E> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <D, E : DataError> Result<D, E>.onError(action: (E) -> Unit): Result<D, E> {
    if (this is Result.Error) action(error)
    return this
}
