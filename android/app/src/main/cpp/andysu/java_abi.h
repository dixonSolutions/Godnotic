/*
 * Godnotic: the contract between a swappable engine (libmain) and the Java side it runs under.
 *
 * A live apply swaps the engine and its game data into the running process even when the release
 * also changed the Java code (EngineSwap.swapEngine): the new engine then runs under the previous
 * release's Java code and resident libraries (loader, libnativeui, SDL) until the next start.
 * The loader compares the new engine's java_abi with the running engine's before it swaps
 * (andysu_loader.c request_swap) and refuses a mismatch, so this number has to change whenever an
 * older Java side can no longer run a newer engine.
 *
 * scripts/test-java-abi.sh fingerprints that surface (JNI lookups, libnativeui entry points,
 * protocol and status-file versions, native declarations) and fails when it moved without the
 * fingerprint here being updated; bump GODNOTIC_JAVA_ABI too unless the change is backward
 * compatible. Releases up to 1.2.294 all reported java_abi 1, whatever their contract.
 */
#ifndef GODNOTIC_JAVA_ABI_H
#define GODNOTIC_JAVA_ABI_H

#define GODNOTIC_JAVA_ABI 2
#define GODNOTIC_JAVA_ABI_FINGERPRINT "bb97cf48f64ecb99f197d3b5e870d5935e8e8ec8858c0ac21df521b15c8c593e"

#endif
