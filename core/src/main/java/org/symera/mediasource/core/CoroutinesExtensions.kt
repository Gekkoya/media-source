package org.symera.mediasource.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

suspend inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll()
}

inline fun <A, B> Iterable<A>.parallelMapBlocking(crossinline f: suspend (A) -> B): List<B> = runBlocking { parallelMap(f) }

suspend inline fun <A, B> Iterable<A>.parallelMapNotNull(crossinline f: suspend (A) -> B?): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll().filterNotNull()
}

inline fun <A, B> Iterable<A>.parallelMapNotNullBlocking(crossinline f: suspend (A) -> B?): List<B> = runBlocking { parallelMapNotNull(f) }

suspend inline fun <A, B> Iterable<A>.parallelFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
    map { async { f(it) } }.awaitAll().flatten()
}

inline fun <A, B> Iterable<A>.parallelFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { parallelFlatMap(f) }

suspend inline fun <A, B> Iterable<A>.parallelCatchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
    map {
        async {
            try {
                f(it)
            } catch (e: Throwable) {
                Log.e("Coroutines", "An error occurred in parallelCatchingFlatMap", e)
                emptyList()
            }
        }
    }.awaitAll().flatten()
}

suspend inline fun <A, B> Iterable<A>.parallelCatchingMapNotNull(crossinline f: suspend (A) -> B?): List<B> = withContext(Dispatchers.IO) {
    map {
        async {
            try {
                f(it)
            } catch (e: Throwable) {
                Log.e("Coroutines", "An error occurred in parallelCatchingMapNotNull", e)
                null
            }
        }
    }.awaitAll().filterNotNull()
}

inline fun <A, B> Iterable<A>.parallelCatchingFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { parallelCatchingFlatMap(f) }

suspend inline fun <A, B> Iterable<A>.catchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = flatMap {
    try {
        f(it)
    } catch (e: Throwable) {
        Log.e("Collections", "An error occurred in catchingFlatMap", e)
        emptyList()
    }
}

inline fun <A, B> Iterable<A>.flatMapCatching(crossinline f: (A) -> Iterable<B>): List<B> = flatMap {
    try {
        f(it)
    } catch (e: Throwable) {
        Log.e("Collections", "An error occurred in flatMapCatching", e)
        emptyList()
    }
}

inline fun <A, B> Iterable<A>.catchingFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { catchingFlatMap(f) }
