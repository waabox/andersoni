package org.waabox.andersoni.leader;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.easymock.EasyMock.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SingleNodeLeaderElection}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SingleNodeLeaderElectionTest {

  @Test
  void whenCheckingIsLeader_givenSingleNode_shouldAlwaysReturnTrue() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    assertTrue(election.isLeader());

    election.start();
    assertTrue(election.isLeader());

    election.stop();
    assertTrue(election.isLeader());
  }

  @Test
  void whenStarting_givenListener_shouldNotifyAsLeader() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    final LeaderChangeListener listener = createMock(LeaderChangeListener.class);
    listener.onLeaderChange(true);
    replay(listener);

    election.onLeaderChange(listener);
    election.start();

    verify(listener);
  }

  @Test
  void whenStarting_givenMultipleListeners_shouldNotifyAll() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    final LeaderChangeListener listener1 =
        createMock(LeaderChangeListener.class);
    final LeaderChangeListener listener2 =
        createMock(LeaderChangeListener.class);

    listener1.onLeaderChange(true);
    listener2.onLeaderChange(true);
    replay(listener1, listener2);

    election.onLeaderChange(listener1);
    election.onLeaderChange(listener2);
    election.start();

    verify(listener1, listener2);
  }

  @Test
  void whenRegistering_givenNullListener_shouldThrowNpe() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    assertThrows(NullPointerException.class,
        () -> election.onLeaderChange(null));
  }

  @Test
  void whenStarting_givenNoListeners_shouldNotThrow() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    election.start();

    assertTrue(election.isLeader());
  }

  @Test
  void whenStartingAgain_givenStopThenStart_shouldReNotifyListeners() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    final LeaderChangeListener listener =
        createMock(LeaderChangeListener.class);
    listener.onLeaderChange(true);
    expectLastCall().times(2);
    replay(listener);

    election.onLeaderChange(listener);
    election.start();
    election.stop();
    election.start();

    verify(listener);
  }

  @Test
  void whenStartingMultipleTimes_givenNoStop_shouldNotifyEachTime() {
    final SingleNodeLeaderElection election = new SingleNodeLeaderElection();

    final LeaderChangeListener listener =
        createMock(LeaderChangeListener.class);
    listener.onLeaderChange(true);
    expectLastCall().times(3);
    replay(listener);

    election.onLeaderChange(listener);
    election.start();
    election.start();
    election.start();

    verify(listener);
  }
}
