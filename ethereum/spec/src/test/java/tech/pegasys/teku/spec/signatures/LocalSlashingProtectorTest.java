/*
 * Copyright Consensys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.spec.signatures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.signingrecord.ValidatorSigningRecord;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.AsyncRunnerFactory;
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.io.SyncDataAccessor;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class LocalSlashingProtectorTest {
  private static final Logger LOG = LogManager.getLogger();
  private static final Bytes32 GENESIS_VALIDATORS_ROOT = Bytes32.fromHexString("0x561234");
  private static final UInt64 ATTESTATION_TEST_BLOCK_SLOT = UInt64.valueOf(3);
  private static final UInt64 BLOCK_TEST_SOURCE_EPOCH = UInt64.valueOf(12);
  private static final UInt64 BLOCK_TEST_TARGET_EPOCH = UInt64.valueOf(15);
  private final DataStructureUtil dataStructureUtil =
      new DataStructureUtil(TestSpecFactory.createDefault());

  private final BLSPublicKey validator = dataStructureUtil.randomPublicKey();
  private final SyncDataAccessor dataWriter = mock(SyncDataAccessor.class);

  private final Path baseDir = Path.of("/data");
  private final Path signingRecordPath =
      baseDir.resolve(validator.toBytesCompressed().toUnprefixedHexString() + ".yml");

  private final LocalSlashingProtector slashingProtectionStorage =
      new LocalSlashingProtector(dataWriter, baseDir, true);

  private final AsyncRunnerFactory asyncRunnerFactory =
      AsyncRunnerFactory.createDefault(new MetricTrackingExecutorFactory(new StubMetricsSystem()));

  final AsyncRunner asyncRunner = asyncRunnerFactory.create("LocalSlashingProtectorTest", 3);

  @ParameterizedTest(name = "maySignBlock({0})")
  @MethodSource("blockCases")
  void maySignBlock(
      @SuppressWarnings("unused") final String name,
      final Optional<UInt64> lastSignedRecord,
      final UInt64 slot,
      final boolean allowed)
      throws Exception {
    if (allowed) {
      assertBlockSigningAllowed(lastSignedRecord, slot);
    } else {
      assertBlockSigningDisallowed(lastSignedRecord, slot);
    }
  }

  static List<Arguments> blockCases() {
    return List.of(
        Arguments.of("noExistingRecord", Optional.empty(), UInt64.valueOf(1), true),
        Arguments.of("=", Optional.of(UInt64.valueOf(3)), UInt64.valueOf(3), false),
        Arguments.of("<", Optional.of(UInt64.valueOf(3)), UInt64.valueOf(2), false),
        Arguments.of(">", Optional.of(UInt64.valueOf(3)), UInt64.valueOf(4), true));
  }

  @Test
  void cannotAccessSameValidatorConcurrently()
      throws ExecutionException, InterruptedException, TimeoutException {
    final AtomicBoolean releaseLock = new AtomicBoolean(false);

    final SafeFuture<Void> firstSigner =
        asyncRunner.runAsync(
            () -> {
              final ReentrantLock lock = slashingProtectionStorage.acquireLock(validator);
              LOG.debug("LOCKED firstSigner");
              do {
                try {
                  Thread.sleep(10);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              } while (!releaseLock.get());
              lock.unlock();
              LOG.debug("UNLOCK firstSigner");
            });

    while (!slashingProtectionStorage.getLock(validator).isLocked()) {
      Thread.sleep(10);
    }
    LOG.debug("firstSigner has the lock");

    assertThat(slashingProtectionStorage.getLock(validator).hasQueuedThreads()).isFalse();

    final SafeFuture<Void> secondSigner =
        asyncRunner.runAsync(
            () -> {
              final ReentrantLock lock = slashingProtectionStorage.acquireLock(validator);
              LOG.debug("LOCKED secondSigner");
              lock.unlock();
              LOG.debug("UNLOCK secondSigner");
            });

    while (!slashingProtectionStorage.getLock(validator).hasQueuedThreads()) {
      Thread.sleep(10);
    }
    LOG.debug("firstSigner waiting on acquire lock");

    assertThat(firstSigner).isNotCompleted();
    assertThat(secondSigner).isNotCompleted();

    releaseLock.set(true);
    firstSigner.get(50, TimeUnit.MILLISECONDS);
    assertThat(firstSigner).isCompleted();
    secondSigner.get(50, TimeUnit.MILLISECONDS);
    assertThat(secondSigner).isCompleted();

    assertThat(slashingProtectionStorage.getLock(validator).hasQueuedThreads()).isFalse();
    assertThat(slashingProtectionStorage.getLock(validator).isLocked()).isFalse();
  }

  @Test
  void canAccessDifferentValidatorConcurrently()
      throws ExecutionException, InterruptedException, TimeoutException {
    final AtomicBoolean releaseLock = new AtomicBoolean(false);
    final SafeFuture<Void> firstSigner =
        asyncRunner.runAsync(
            () -> {
              final ReentrantLock lock = slashingProtectionStorage.acquireLock(validator);
              LOG.debug("LOCKED firstSigner");
              do {
                try {
                  Thread.sleep(10);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              } while (!releaseLock.get());
              lock.unlock();
              LOG.debug("UNLOCK firstSigner");
            });
    final SafeFuture<Void> secondSigner =
        asyncRunner.runAsync(
            () -> {
              final ReentrantLock lock =
                  slashingProtectionStorage.acquireLock(dataStructureUtil.randomPublicKey());
              LOG.debug("LOCKED secondSigner");
              lock.unlock();
              LOG.debug("UNLOCK secondSigner");
            });
    assertThat(firstSigner).isNotCompleted();
    secondSigner.get(50, TimeUnit.MILLISECONDS);
    assertThat(secondSigner).isCompleted();
    releaseLock.set(true);
    firstSigner.get(50, TimeUnit.MILLISECONDS);
    assertThat(firstSigner).isCompleted();
  }

  @ParameterizedTest(name = "maySignAttestation({0})")
  @MethodSource("attestationCases")
  void maySignAttestation(
      @SuppressWarnings("unused") final String name,
      final Optional<ValidatorSigningRecord> lastSignedRecord,
      final UInt64 sourceEpoch,
      final UInt64 targetEpoch,
      final boolean allowed)
      throws Exception {
    if (allowed) {
      assertAttestationSigningAllowed(lastSignedRecord, sourceEpoch, targetEpoch);
    } else {
      assertAttestationSigningDisallowed(lastSignedRecord, sourceEpoch, targetEpoch);
    }
  }

  static List<Arguments> attestationCases() {
    final Optional<ValidatorSigningRecord> existingRecord =
        Optional.of(
            new ValidatorSigningRecord(
                GENESIS_VALIDATORS_ROOT,
                ATTESTATION_TEST_BLOCK_SLOT,
                UInt64.valueOf(4),
                UInt64.valueOf(6)));
    return List.of(
        // No record
        Arguments.of(
            "noExistingRecord", Optional.empty(), UInt64.valueOf(1), UInt64.valueOf(2), true),
        attestationArguments("=", "=", existingRecord, 4, 6, false),
        attestationArguments("=", "<", existingRecord, 4, 5, false),
        attestationArguments("=", ">", existingRecord, 4, 7, true),
        attestationArguments("<", "=", existingRecord, 3, 6, false),
        attestationArguments("<", "<", existingRecord, 3, 5, false),
        attestationArguments("<", ">", existingRecord, 3, 7, false),
        attestationArguments(">", "=", existingRecord, 5, 6, false),
        attestationArguments(">", "<", existingRecord, 5, 5, false),
        attestationArguments(">", ">", existingRecord, 5, 7, true));
  }

  private static Arguments attestationArguments(
      final String sourceEpochDescription,
      final String targetEpochDescription,
      final Optional<ValidatorSigningRecord> lastSignedRecord,
      final int sourceEpoch,
      final int targetEpoch,
      final boolean allowed) {
    return Arguments.of(
        "source " + sourceEpochDescription + ", target " + targetEpochDescription,
        lastSignedRecord,
        UInt64.valueOf(sourceEpoch),
        UInt64.valueOf(targetEpoch),
        allowed);
  }

  private void assertAttestationSigningAllowed(
      final Optional<ValidatorSigningRecord> lastSignedAttestation,
      final UInt64 sourceEpoch,
      final UInt64 targetEpoch)
      throws Exception {
    when(dataWriter.read(signingRecordPath))
        .thenReturn(lastSignedAttestation.map(ValidatorSigningRecord::toBytes));

    assertThat(
            slashingProtectionStorage.maySignAttestation(
                validator, GENESIS_VALIDATORS_ROOT, sourceEpoch, targetEpoch))
        .isCompletedWithValue(true);

    final ValidatorSigningRecord updatedRecord =
        new ValidatorSigningRecord(
            GENESIS_VALIDATORS_ROOT,
            lastSignedAttestation.isPresent() ? ATTESTATION_TEST_BLOCK_SLOT : UInt64.ZERO,
            sourceEpoch,
            targetEpoch);
    verify(dataWriter).syncedWrite(signingRecordPath, updatedRecord.toBytes());
  }

  private void assertAttestationSigningDisallowed(
      final Optional<ValidatorSigningRecord> lastSignedAttestation,
      final UInt64 sourceEpoch,
      final UInt64 targetEpoch)
      throws IOException {
    when(dataWriter.read(signingRecordPath))
        .thenReturn(lastSignedAttestation.map(ValidatorSigningRecord::toBytes));

    assertThat(
            slashingProtectionStorage.maySignAttestation(
                validator, GENESIS_VALIDATORS_ROOT, sourceEpoch, targetEpoch))
        .isCompletedWithValue(false);
    verify(dataWriter, never()).syncedWrite(any(), any());
  }

  private void assertBlockSigningAllowed(
      final Optional<UInt64> lastSignedBlockSlot, final UInt64 newBlockSlot) throws Exception {
    when(dataWriter.read(signingRecordPath))
        .thenReturn(lastSignedBlockSlot.map(this::blockTestSigningRecord));

    assertThat(
            slashingProtectionStorage.maySignBlock(
                validator, GENESIS_VALIDATORS_ROOT, newBlockSlot))
        .isCompletedWithValue(true);

    final Bytes updatedRecord =
        lastSignedBlockSlot.isPresent()
            ? blockTestSigningRecord(newBlockSlot)
            : new ValidatorSigningRecord(
                    GENESIS_VALIDATORS_ROOT,
                    newBlockSlot,
                    ValidatorSigningRecord.NEVER_SIGNED,
                    ValidatorSigningRecord.NEVER_SIGNED)
                .toBytes();
    verify(dataWriter).syncedWrite(signingRecordPath, updatedRecord);
  }

  private Bytes blockTestSigningRecord(final UInt64 blockSlot) {
    return new ValidatorSigningRecord(
            GENESIS_VALIDATORS_ROOT, blockSlot, BLOCK_TEST_SOURCE_EPOCH, BLOCK_TEST_TARGET_EPOCH)
        .toBytes();
  }

  private void assertBlockSigningDisallowed(
      final Optional<UInt64> lastSignedBlockSlot, final UInt64 newBlockSlot) throws Exception {
    when(dataWriter.read(signingRecordPath))
        .thenReturn(lastSignedBlockSlot.map(this::blockTestSigningRecord));

    assertThat(
            slashingProtectionStorage.maySignBlock(
                validator, GENESIS_VALIDATORS_ROOT, newBlockSlot))
        .isCompletedWithValue(false);

    verify(dataWriter, never()).syncedWrite(any(), any());
  }
}
