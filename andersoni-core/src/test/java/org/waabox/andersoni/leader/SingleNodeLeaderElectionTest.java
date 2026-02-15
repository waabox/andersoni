package org.waabox.andersoni.leader;

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
}
