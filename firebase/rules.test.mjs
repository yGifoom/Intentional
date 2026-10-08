import { before, after, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, writeBatch, serverTimestamp, Timestamp, Bytes } from 'firebase/firestore';

let env;
const uid = 'installation-1';
const participantId = '12345678-1234-1234-1234-123456789abc';
const first = '12345678-1234-1234-1234-123456789001';
const second = '12345678-1234-1234-1234-123456789002';
const path = `intentionalStudyParticipants/${uid}`;
const record = (bytes = new Uint8Array([1])) => ({ participantId, schemaVersion: 1, contentType: 'text/csv',
  csv: Bytes.fromUint8Array(bytes), sha256: 'a'.repeat(64), uploadedAt: serverTimestamp() });
function submission(db, id = first, data = record()) {
  const batch = writeBatch(db);
  batch.set(doc(db, `${path}/intentionalStudyExports/${id}`), data);
  batch.set(doc(db, path), { participantId, lastSubmissionId: id, lastSubmittedAt: serverTimestamp() });
  return batch.commit();
}
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-intentional', firestore: {
    rules: readFileSync(new URL('./firestore.rules', import.meta.url), 'utf8')
  } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
test('authenticated atomic submission succeeds', async () => {
  await assertSucceeds(submission(env.authenticatedContext(uid).firestore()));
});
test('anonymous unauthenticated requests and another UID are rejected', async () => {
  await assertFails(submission(env.unauthenticatedContext().firestore()));
  await assertFails(submission(env.authenticatedContext('someone-else').firestore()));
});
test('a second submission inside 15 minutes fails', async () => {
  const db = env.authenticatedContext(uid).firestore();
  await assertSucceeds(submission(db)); await assertFails(submission(db, second));
});
test('submission after 15 minutes succeeds', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), path), { participantId, lastSubmissionId: first,
      lastSubmittedAt: Timestamp.fromMillis(Date.now() - 901_000) });
  });
  await assertSucceeds(submission(env.authenticatedContext(uid).firestore(), second));
});
test('parallel submissions allow only one winner', async () => {
  const db = env.authenticatedContext(uid).firestore();
  const results = await Promise.allSettled([submission(db), submission(db, second)]);
  if (results.filter(result => result.status === 'fulfilled').length !== 1) throw new Error('Concurrent cooldown bypass');
});
test('512 KiB succeeds, one byte more and empty files fail', async () => {
  const db = env.authenticatedContext(uid).firestore();
  await assertFails(submission(db, first, record(new Uint8Array(524289))));
  await assertFails(submission(db, first, record(new Uint8Array())));
  await assertSucceeds(submission(db, first, record(new Uint8Array(524288))));
});
test('records are private and immutable, own limiter alone is readable', async () => {
  const db = env.authenticatedContext(uid).firestore(); await submission(db);
  await assertSucceeds(getDoc(doc(db, path)));
  const ref = doc(db, `${path}/intentionalStudyExports/${first}`);
  await assertFails(getDoc(ref)); await assertFails(setDoc(ref, record())); await assertFails(deleteDoc(ref));
  await assertFails(getDoc(doc(env.authenticatedContext('someone-else').firestore(), path)));
});
test('an upload cannot omit or forge the limiter or timestamp', async () => {
  const db = env.authenticatedContext(uid).firestore();
  await assertFails(setDoc(doc(db, `${path}/intentionalStudyExports/${first}`), record()));
  await assertFails(submission(db, first, { ...record(), uploadedAt: Timestamp.fromMillis(0) }));
  await assertFails(setDoc(doc(db, path), { participantId, lastSubmissionId: first, lastSubmittedAt: serverTimestamp() }));
});
test('fake types, extra fields, and multiple uploads in a batch fail', async () => {
  const db = env.authenticatedContext(uid).firestore();
  await assertFails(submission(db, first, { ...record(), csv: 'not bytes' }));
  await assertFails(submission(db, first, { ...record(), extra: true }));
  const batch = writeBatch(db);
  for (const id of [first, second]) batch.set(doc(db, `${path}/intentionalStudyExports/${id}`), record());
  batch.set(doc(db, path), { participantId, lastSubmissionId: second, lastSubmittedAt: serverTimestamp() });
  await assertFails(batch.commit());
});
test('canonical latest CSV is created, then replaced only after the cooldown', async () => {
  const db = env.authenticatedContext(uid).firestore();
  await assertSucceeds(submission(db, 'latest'));
  await assertFails(submission(db, 'latest', record(new Uint8Array([2]))));
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), path), { participantId, lastSubmissionId: 'latest',
      lastSubmittedAt: Timestamp.fromMillis(Date.now() - 901_000) });
  });
  await assertSucceeds(submission(db, 'latest', record(new Uint8Array([2]))));
});
test('latest CSV cannot be changed alone, read, deleted, or written by another account', async () => {
  const db = env.authenticatedContext(uid).firestore(); await submission(db, 'latest');
  const ref = doc(db, `${path}/intentionalStudyExports/latest`);
  await assertFails(setDoc(ref, record())); await assertFails(getDoc(ref)); await assertFails(deleteDoc(ref));
  await assertFails(submission(env.authenticatedContext('someone-else').firestore(), 'latest'));
});
test('a legacy installation can move to latest without deleting its old snapshot', async () => {
  const db = env.authenticatedContext(uid).firestore(); await submission(db);
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), path), { participantId, lastSubmissionId: first,
      lastSubmittedAt: Timestamp.fromMillis(Date.now() - 901_000) });
  });
  await assertSucceeds(submission(db, 'latest'));
  await assertFails(setDoc(doc(db, `${path}/intentionalStudyExports/${first}`), record()));
});
test('parallel first latest uploads allow at most one winner', async () => {
  const db = env.authenticatedContext(uid).firestore();
  const results = await Promise.allSettled([submission(db, 'latest'), submission(db, 'latest')]);
  if (results.filter(result => result.status === 'fulfilled').length !== 1) throw new Error('Concurrent latest cooldown bypass');
});
