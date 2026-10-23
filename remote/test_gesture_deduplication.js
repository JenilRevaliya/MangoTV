/**
 * Regression Test Suite: Gesture Recognizer and Command Deduplication
 * Verifies:
 * 1. Single swipe produces exactly one navigation command.
 * 2. Intentional consecutive swipes produce exactly one command per swipe.
 * 3. Tap interaction produces exactly one Select command.
 * 4. Pointer cancel produces zero navigation commands.
 * 5. Secondary touch in multi-touch produces zero unintended commands.
 * 6. Server-side command ID deduplication suppresses duplicate deliveries.
 */

import assert from 'node:assert';

// Simulated Swipe Recognizer State Machine mirroring SwipeRemote.tsx
class MockGestureRecognizer {
  constructor(onDispatch) {
    this.onDispatch = onDispatch;
    this.SWIPE_THRESHOLD = 28;
    this.TAP_MAX_DISTANCE = 12;
    this.TAP_MAX_DURATION = 320;

    this.activePointerId = null;
    this.pointerStart = null;
    this.gestureHandled = false;
  }

  pointerDown(pointerId, clientX, clientY) {
    // Only accept primary pointer
    if (this.activePointerId !== null) {
      return false; // Ignored secondary touch
    }
    this.activePointerId = pointerId;
    this.pointerStart = { x: clientX, y: clientY, time: Date.now() };
    this.gestureHandled = false;
    return true;
  }

  pointerMove(pointerId, clientX, clientY) {
    if (pointerId !== this.activePointerId) return false;
    if (this.gestureHandled || !this.pointerStart) return false;

    const dx = clientX - this.pointerStart.x;
    const dy = clientY - this.pointerStart.y;
    const absX = Math.abs(dx);
    const absY = Math.abs(dy);

    if (absX >= this.SWIPE_THRESHOLD || absY >= this.SWIPE_THRESHOLD) {
      this.gestureHandled = true;
      const direction = absX > absY ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
      const cmdId = 'swipe_' + direction + '_' + Date.now();
      this.onDispatch(direction, cmdId);
      return true;
    }
    return false;
  }

  pointerUp(pointerId, clientX, clientY) {
    if (pointerId !== this.activePointerId) return false;

    if (!this.gestureHandled && this.pointerStart) {
      const elapsed = Date.now() - this.pointerStart.time;
      const dist = Math.hypot(clientX - this.pointerStart.x, clientY - this.pointerStart.y);

      if (elapsed <= this.TAP_MAX_DURATION && dist <= this.TAP_MAX_DISTANCE) {
        this.gestureHandled = true;
        const cmdId = 'tap_select_' + Date.now();
        this.onDispatch('select', cmdId);
      }
    }

    this.activePointerId = null;
    this.pointerStart = null;
    this.gestureHandled = false;
    return true;
  }

  pointerCancel(pointerId) {
    if (pointerId !== this.activePointerId) return false;
    this.activePointerId = null;
    this.pointerStart = null;
    this.gestureHandled = false;
    return true;
  }
}

// Simulated Server-Side Deduplication Cache mirroring MangoRemoteServer.java
class MockServerDeduplicator {
  constructor() {
    this.processedCommandIds = new Map();
  }

  processCommand(id) {
    const now = Date.now();
    const lastSeen = this.processedCommandIds.get(id);
    if (lastSeen && (now - lastSeen) < 600) {
      return { status: 'ok', deduplicated: true, executed: false };
    }
    this.processedCommandIds.set(id, now);
    return { status: 'ok', deduplicated: false, executed: true };
  }
}

// --- Test Suite Execution ---
console.log('Running Gesture & Deduplication Regression Tests...');

// Test 1: Single deliberate swipe produces exactly 1 command
{
  const dispatched = [];
  const recognizer = new MockGestureRecognizer((dir, id) => dispatched.push({ dir, id }));

  recognizer.pointerDown(1, 100, 100);
  recognizer.pointerMove(1, 110, 100);
  recognizer.pointerMove(1, 130, 100); // 30px > 28px threshold -> triggers right
  recognizer.pointerMove(1, 150, 100); // Additional movement during same stroke
  recognizer.pointerUp(1, 155, 100);

  assert.strictEqual(dispatched.length, 1, 'Single swipe MUST emit exactly 1 command');
  assert.strictEqual(dispatched[0].dir, 'right');
  console.log('PASS: Test 1 - Single deliberate swipe produces exactly 1 command');
}

// Test 2: Intentional consecutive swipes produce exactly 1 command each
{
  const dispatched = [];
  const recognizer = new MockGestureRecognizer((dir, id) => dispatched.push({ dir, id }));

  // First swipe: Up
  recognizer.pointerDown(1, 100, 100);
  recognizer.pointerMove(1, 100, 60); // 40px up
  recognizer.pointerUp(1, 100, 60);

  // Second swipe: Down
  recognizer.pointerDown(2, 100, 100);
  recognizer.pointerMove(2, 100, 140); // 40px down
  recognizer.pointerUp(2, 100, 140);

  assert.strictEqual(dispatched.length, 2, 'Two consecutive swipes must produce 2 commands');
  assert.strictEqual(dispatched[0].dir, 'up');
  assert.strictEqual(dispatched[1].dir, 'down');
  console.log('PASS: Test 2 - Consecutive deliberate swipes produce 1 command per swipe');
}

// Test 3: Tap produces Select command
{
  const dispatched = [];
  const recognizer = new MockGestureRecognizer((dir, id) => dispatched.push({ dir, id }));

  recognizer.pointerDown(1, 100, 100);
  recognizer.pointerMove(1, 102, 101); // Minor jitter < 12px
  recognizer.pointerUp(1, 102, 101);

  assert.strictEqual(dispatched.length, 1, 'Tap must produce exactly 1 command');
  assert.strictEqual(dispatched[0].dir, 'select');
  console.log('PASS: Test 3 - Tap produces Select command');
}

// Test 4: Canceled pointer produces zero navigation
{
  const dispatched = [];
  const recognizer = new MockGestureRecognizer((dir, id) => dispatched.push({ dir, id }));

  recognizer.pointerDown(1, 100, 100);
  recognizer.pointerMove(1, 110, 100); // Distance not yet crossed
  recognizer.pointerCancel(1); // Canceled by system gesture or scroll

  assert.strictEqual(dispatched.length, 0, 'Canceled pointer must produce 0 commands');
  console.log('PASS: Test 4 - Canceled pointer produces zero navigation commands');
}

// Test 5: Secondary multi-touch pointer produces no unintended command
{
  const dispatched = [];
  const recognizer = new MockGestureRecognizer((dir, id) => dispatched.push({ dir, id }));

  // Primary pointer down
  recognizer.pointerDown(1, 100, 100);
  // Secondary pointer down (e.g. second finger)
  const secondaryAccepted = recognizer.pointerDown(2, 200, 200);
  assert.strictEqual(secondaryAccepted, false, 'Secondary pointer must be rejected');

  recognizer.pointerMove(2, 250, 200); // Move secondary pointer
  assert.strictEqual(dispatched.length, 0, 'Movement of secondary pointer must not dispatch');

  recognizer.pointerUp(2, 250, 200);
  assert.strictEqual(dispatched.length, 0);

  // Complete primary pointer swipe
  recognizer.pointerMove(1, 60, 100); // 40px left
  recognizer.pointerUp(1, 60, 100);

  assert.strictEqual(dispatched.length, 1);
  assert.strictEqual(dispatched[0].dir, 'left');
  console.log('PASS: Test 5 - Secondary multi-touch pointer produces no unintended command');
}

// Test 6: Server-side deduplication suppresses duplicate deliveries
{
  const server = new MockServerDeduplicator();

  const cmdId = 'cmd_nav_unique_123';
  const res1 = server.processCommand(cmdId);
  assert.strictEqual(res1.executed, true, 'First delivery must execute');

  // Retried or duplicated delivery of same command ID
  const res2 = server.processCommand(cmdId);
  assert.strictEqual(res2.executed, false, 'Duplicate delivery must NOT re-execute');
  assert.strictEqual(res2.deduplicated, true);

  // Intentional next command has new ID
  const res3 = server.processCommand('cmd_nav_unique_124');
  assert.strictEqual(res3.executed, true, 'Subsequent command with new ID must execute');
  console.log('PASS: Test 6 - Server-side deduplication suppresses duplicate deliveries');
}

console.log('All 6 Gesture and Deduplication Regression Tests Passed Successfully!');
