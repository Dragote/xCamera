package com.dragote.xcamera.shared.common.domain.result

sealed interface DataError {

    enum class Network : DataError {
        NO_INTERNET,
        SERVER_ERROR,
        SERIALIZATION,
        UNKNOWN,
    }

    enum class Local : DataError {
        DISK_FULL,
        UNKNOWN,
    }
}
