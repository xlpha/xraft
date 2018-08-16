package in.xnnyygn.xraft.core.node;

import com.google.common.collect.ImmutableSet;
import in.xnnyygn.xraft.core.log.entry.*;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryBatchRemovedEvent;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryCommittedEvent;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryFromLeaderAppendEvent;
import in.xnnyygn.xraft.core.log.replication.ReplicatingState;
import in.xnnyygn.xraft.core.rpc.ConnectorAdapter;
import in.xnnyygn.xraft.core.rpc.MockConnector;
import in.xnnyygn.xraft.core.rpc.message.*;
import in.xnnyygn.xraft.core.schedule.NullScheduler;
import in.xnnyygn.xraft.core.support.DirectTaskExecutor;
import in.xnnyygn.xraft.core.support.ListeningTaskExecutor;
import in.xnnyygn.xraft.core.support.TaskExecutor;
import org.junit.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class NodeImplTest {

    private static class WaitableConnector extends ConnectorAdapter {

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void sendAppendEntries(AppendEntriesRpc rpc, NodeId destinationNodeId) {
            latch.countDown();
        }

        @Override
        public void sendAppendEntries(AppendEntriesRpc rpc, NodeEndpoint destinationEndpoint) {
            latch.countDown();
        }

        void awaitAppendEntriesRpc() throws InterruptedException {
            latch.await();
        }

    }

    private static TaskExecutor taskExecutor;
    private static TaskExecutor groupConfigChangeTaskExecutor;

    private NodeBuilder newNodeBuilder(NodeId selfId, NodeEndpoint... endpoints) {
        return new NodeBuilder(selfId, new NodeGroup(Arrays.asList(endpoints)))
                .setScheduler(new NullScheduler())
                .setConnector(new MockConnector())
                .setTaskExecutor(new DirectTaskExecutor(true));
    }

    private AppendEntriesRpc createAppendEntriesRpc(int lastEntryIndex) {
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setPrevLogIndex(lastEntryIndex);
        return rpc;
    }

    private void checkWithinTaskExecutor(NodeImpl node, Runnable r) throws Throwable {
        try {
            node.getContext().taskExecutor().submit(r).get();
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        taskExecutor = new ListeningTaskExecutor(Executors.newSingleThreadExecutor(r -> new Thread(r, "node-test")));
        groupConfigChangeTaskExecutor = new ListeningTaskExecutor(Executors.newSingleThreadExecutor(r -> new Thread(r, "group-config-change-test")));
    }

    @Test
    public void testStartFresh() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .build();

        node.start();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(0, state.getTerm());
        Assert.assertNull(state.getVotedFor());
    }

    @Test
    public void testStartLoadFromStore() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .setStore(new MemoryNodeStore(1, NodeId.of("B")))
                .build();

        node.start();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());
        Assert.assertEquals(NodeId.of("B"), state.getVotedFor());
    }

    @Test
    public void testElectionTimeoutStandalone() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .build();
        node.start();

        node.electionTimeout();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.LEADER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());

        // replication state set
        Assert.assertNotNull(node.getContext().group().findReplicationState(NodeId.of("A")));

        // no-op log
        EntryMeta lastEntryMeta = node.getContext().log().getLastEntryMeta();
        Assert.assertEquals(Entry.KIND_NO_OP, lastEntryMeta.getKind());
        Assert.assertEquals(1, lastEntryMeta.getIndex());
        Assert.assertEquals(1, lastEntryMeta.getTerm());
    }

    @Test
    public void testElectionTimeoutStandby() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .setStore(new MemoryNodeStore(1, null))
                .setStandby(true)
                .build();
        node.start();
        node.electionTimeout();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());
        // timeout was cancelled
    }

    @Test
    public void testElectionTimeoutWhenLeader() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .build();
        node.start();
        node.electionTimeout();
        node.electionTimeout(); // do nothing
    }

    @Test
    public void testElectionTimeoutWhenFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.CANDIDATE, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());
        Assert.assertEquals(1, state.getVotesCount());

        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteRpc rpc = (RequestVoteRpc) mockConnector.getRpc();
        Assert.assertEquals(1, rpc.getTerm());
        Assert.assertEquals(NodeId.of("A"), rpc.getCandidateId());
        Assert.assertEquals(0, rpc.getLastLogIndex());
        Assert.assertEquals(0, rpc.getLastLogTerm());
    }

    @Test
    public void testElectionTimeoutWhenCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // become candidate
        node.electionTimeout();

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.CANDIDATE, state.getRoleName());
        Assert.assertEquals(2, state.getTerm());
        Assert.assertEquals(1, state.getVotesCount());

        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteRpc rpc = (RequestVoteRpc) mockConnector.getLastMessage().getRpc();
        Assert.assertEquals(2, rpc.getTerm());
        Assert.assertEquals(NodeId.of("A"), rpc.getCandidateId());
        Assert.assertEquals(0, rpc.getLastLogIndex());
        Assert.assertEquals(0, rpc.getLastLogTerm());
    }

    @Test
    public void testReplicateLogStandalone() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .build();
        node.start();
        node.electionTimeout();
        Assert.assertEquals(0, node.getContext().log().getCommitIndex());
        node.replicateLog();
        Assert.assertEquals(1, node.getContext().log().getCommitIndex());
    }

    @Test
    public void testReplicateLog() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // request vote rpc
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));
        node.replicateLog(); // append entries * 2

        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        Assert.assertEquals(3, mockConnector.getMessageCount());

        // check destination node id
        List<MockConnector.Message> messages = mockConnector.getMessages();
        Set<NodeId> destinationNodeIds = messages.subList(1, 3).stream()
                .map(MockConnector.Message::getDestinationNodeId)
                .collect(Collectors.toSet());
        Assert.assertEquals(2, destinationNodeIds.size());
        Assert.assertTrue(destinationNodeIds.contains(NodeId.of("B")));
        Assert.assertTrue(destinationNodeIds.contains(NodeId.of("C")));

        AppendEntriesRpc rpc = (AppendEntriesRpc) messages.get(2).getRpc();
        Assert.assertEquals(1, rpc.getTerm());
    }

    @Test
    public void testReplicateLogSkipReplicating() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));
        node.getContext().group().findReplicationState(NodeId.of("B")).startReplicating();
        node.replicateLog();

        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        // request vote rpc + append entries rpc
        Assert.assertEquals(2, mockConnector.getMessageCount());
    }

    @Test
    public void testReplicateLogForceReplicating() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));
        long replicatedAt = System.currentTimeMillis() - node.getContext().config().getMinReplicationInterval() - 1;
        node.getContext().group().findReplicationState(NodeId.of("B")).startReplicating(replicatedAt);
        node.replicateLog();

        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        // request vote rpc + append entries rpc * 2
        Assert.assertEquals(3, mockConnector.getMessageCount());
    }

    @Test(expected = NotLeaderException.class)
    public void testAppendLogWhenFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.appendLog("test".getBytes());
    }

    @Test(expected = NotLeaderException.class)
    public void testAppendLogWhenCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();
        node.appendLog("test".getBytes());
    }


    @Test
    public void testAppendLog() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        node.appendLog("test".getBytes());
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        // request vote rpc + append entries * 2
        Assert.assertEquals(3, mockConnector.getMessageCount());
    }

    @Test(expected = NotLeaderException.class)
    public void testAddNodeWhenFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.addNode(new NodeEndpoint("D", "localhost", 2336));
    }

    @Test(expected = NotLeaderException.class)
    public void testAddNodeWhenCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();
        node.addNode(new NodeEndpoint("D", "localhost", 2336));
    }

    @Test
    public void testAddNode() throws Throwable {
        WaitableConnector connector = new WaitableConnector();
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConnector(connector)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();

        GroupConfigChangeTaskReference reference = node.addNode(new NodeEndpoint("D", "localhost", 2336));
        connector.awaitAppendEntriesRpc();

        // catch up
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("D"), createAppendEntriesRpc(1)));

        // send replication to B, C, D
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("B"), createAppendEntriesRpc(2)));

        Assert.assertEquals(GroupConfigChangeTaskResult.OK, reference.getResult(1000L));
        checkWithinTaskExecutor(node, ()->{
            Assert.assertEquals(4, node.getContext().group().getCountOfMajor());
        });
    }

    @Test
    public void testAddNodeCannotCatchUp() throws TimeoutException, InterruptedException, ExecutionException {
        NodeConfig config = new NodeConfig();
        config.setNewNodeMaxRound(1);

        WaitableConnector connector = new WaitableConnector();
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConfig(config)
                .setConnector(connector)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();

        GroupConfigChangeTaskReference reference = node.addNode(new NodeEndpoint("D", "localhost", 2336));
        connector.awaitAppendEntriesRpc();
        // cannot catch up
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("D"), createAppendEntriesRpc(0)));

        Assert.assertEquals(GroupConfigChangeTaskResult.TIMEOUT, reference.getResult(1000L));
    }

    @Test
    public void testAddNodeAwaitPreviousGroupConfigChange() throws TimeoutException, InterruptedException, ExecutionException {
        NodeConfig config = new NodeConfig();
        config.setPreviousGroupConfigChangeTimeout(1);
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConfig(config)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();

        node.addNode(new NodeEndpoint("D", "localhost", 2336));
        GroupConfigChangeTaskReference taskE = node.addNode(new NodeEndpoint("E", "localhost", 2337));
        Assert.assertEquals(GroupConfigChangeTaskResult.TIMEOUT, taskE.getResult(1000L));
        node.cancelGroupConfigChangeTask();
    }

    @Test(expected = NotLeaderException.class)
    public void testRemoveNodeWhenFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.removeNode(NodeId.of("A"));
    }

    @Test(expected = NotLeaderException.class)
    public void testRemoveNodeWhenCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout();
        node.removeNode(NodeId.of("A"));
    }

    @Test
    public void testRemoveNode() throws Throwable {
        WaitableConnector connector = new WaitableConnector();
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConnector(connector)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get(); // become leader, add no-op log
        GroupConfigChangeTaskReference reference = node.removeNode(NodeId.of("B"));
        connector.awaitAppendEntriesRpc();
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("C"), createAppendEntriesRpc(2)));
        Assert.assertEquals(GroupConfigChangeTaskResult.OK, reference.getResult(1000L));
        checkWithinTaskExecutor(node, ()->{
            Assert.assertEquals(2, node.getContext().group().getCountOfMajor());
            Assert.assertNull(node.getContext().group().getState(NodeId.of("B")));
        });
    }

    @Test
    public void testRemoveNodeSelf() throws Throwable {
        WaitableConnector connector = new WaitableConnector();
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConnector(connector)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();
        GroupConfigChangeTaskReference reference = node.removeNode(NodeId.of("A"));
        connector.awaitAppendEntriesRpc();
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("B"), createAppendEntriesRpc(2)));
        Assert.assertEquals(GroupConfigChangeTaskResult.OK, reference.getResult(1000L));
        checkWithinTaskExecutor(node, ()->{
            Assert.assertEquals(2, node.getContext().group().getCountOfMajor());
            Assert.assertNull(node.getContext().group().getState(NodeId.of("A")));
        });
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());
    }

    @Test
    public void testRemoveNodeAppendEntriesResultFromRemovingNode() throws ExecutionException, InterruptedException {
        WaitableConnector connector = new WaitableConnector();
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConnector(connector)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();
        node.removeNode(NodeId.of("B"));
        connector.awaitAppendEntriesRpc();
        node.processAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("B"), createAppendEntriesRpc(2))).get();
        node.cancelGroupConfigChangeTask();
    }

    @Test
    public void testRemoveNodeAwaitPreviousGroupConfigChange() throws TimeoutException, InterruptedException, ExecutionException {
        NodeConfig config = new NodeConfig();
        config.setPreviousGroupConfigChangeTimeout(1);
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setConfig(config)
                .setTaskExecutor(taskExecutor)
                .setGroupConfigChangeTaskExecutor(groupConfigChangeTaskExecutor)
                .build();
        node.start();
        node.electionTimeout();
        node.processRequestVoteResult(new RequestVoteResult(1, true)).get();
        node.removeNode(NodeId.of("B"));
        GroupConfigChangeTaskReference reference = node.removeNode(NodeId.of("B"));
        Assert.assertEquals(GroupConfigChangeTaskResult.TIMEOUT, reference.getResult(1000L));
        node.cancelGroupConfigChangeTask();
    }

    @Test
    public void testOnReceiveRequestVoteRpcNotMajor() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.getContext().group().downgrade(NodeId.of("C"));
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(
                new RequestVoteRpc(), NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcUnknownNode() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(
                new RequestVoteRpc(), NodeId.of("D"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcSmallerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(2, null))
                .build();
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(1);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(2);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(1);
        rpc.setLastLogTerm(2);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertTrue(result.isVoteGranted());
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(NodeId.of("C"), state.getVotedFor());
        Assert.assertEquals(NodeId.of("C"), node.getContext().store().getVotedFor());
    }

    @Test
    public void testOnReceiveRequestVoteRpcLargerTermButNotVote() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.getContext().log().appendEntry(1);
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(2);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(1);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertTrue(result.isVoteGranted());
        Assert.assertEquals(NodeId.of("C"), node.getRoleState().getVotedFor());
    }

    @Test
    public void testOnReceiveRequestVoteRpcFollowerVoted() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, NodeId.of("C")))
                .build();
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(1);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertTrue(result.isVoteGranted());
        Assert.assertEquals(NodeId.of("C"), node.getRoleState().getVotedFor());
    }

    @Test
    public void testOnReceiveRequestVoteRpcFollowerNotVote() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.getContext().log().appendEntry(1);
        node.start();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(1);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.electionTimeout();
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(2);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteRpcLeader() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.electionTimeout();
        node.onReceiveRequestVoteResult(new RequestVoteResult(2, true));
        RequestVoteRpc rpc = new RequestVoteRpc();
        rpc.setTerm(2);
        rpc.setCandidateId(NodeId.of("C"));
        rpc.setLastLogIndex(0);
        rpc.setLastLogTerm(0);
        node.onReceiveRequestVoteRpc(new RequestVoteRpcMessage(rpc, NodeId.of("C"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        RequestVoteResult result = (RequestVoteResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isVoteGranted());
    }

    @Test
    public void testOnReceiveRequestVoteResult() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();

        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));

        // role state
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.LEADER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());

        // replication state set
        Assert.assertNotNull(node.getContext().group().findReplicationState(NodeId.of("A")));
        Assert.assertNotNull(node.getContext().group().findReplicationState(NodeId.of("B")));
        Assert.assertNotNull(node.getContext().group().findReplicationState(NodeId.of("C")));

        // no-op log
        EntryMeta lastEntryMeta = node.getContext().log().getLastEntryMeta();
        Assert.assertEquals(Entry.KIND_NO_OP, lastEntryMeta.getKind());
        Assert.assertEquals(1, lastEntryMeta.getIndex());
        Assert.assertEquals(1, lastEntryMeta.getTerm());
    }

    @Test
    public void testOnReceiveRequestVoteResultNotGranted() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout();

        node.onReceiveRequestVoteResult(new RequestVoteResult(1, false)); // do nothing
    }

    @Test
    public void testOnReceiveRequestVoteResultLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // become candidate

        node.onReceiveRequestVoteResult(new RequestVoteResult(2, false));
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(2, state.getTerm());
    }

    @Test
    public void testOnReceiveRequestVoteResultWhenLeader() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .build();
        node.start();
        node.electionTimeout(); // become leader

        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // do nothing
    }

    @Test
    public void testOnReceiveRequestVoteResultWhenFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(NodeId.of("A"), new NodeEndpoint("A", "localhost", 2333))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();

        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));
    }

    @Test
    public void testOnReceiveRequestVoteResultLessThanHalfOfMajorCount() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2333),
                new NodeEndpoint("C", "localhost", 2333),
                new NodeEndpoint("D", "localhost", 2333),
                new NodeEndpoint("E", "localhost", 2333)
        ).setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.electionTimeout();

        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true));
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.CANDIDATE, state.getRoleName());
        Assert.assertEquals(2, state.getVotesCount());
    }

    @Test
    public void testOnReceiveAppendEntriesRpcSmallerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(2, null))
                .build();
        node.start();
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setTerm(1);
        node.onReceiveAppendEntriesRpc(new AppendEntriesRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector connector = (MockConnector) node.getContext().connector();
        AppendEntriesResult result = (AppendEntriesResult) connector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isSuccess());
    }

    @Test
    public void testOnReceiveAppendEntriesRpcLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setTerm(2);
        rpc.setLeaderId(NodeId.of("B"));
        node.onReceiveAppendEntriesRpc(new AppendEntriesRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector connector = (MockConnector) node.getContext().connector();
        AppendEntriesResult result = (AppendEntriesResult) connector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertTrue(result.isSuccess());

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(NodeId.of("B"), state.getLeaderId());
    }

    @Test
    public void testOnReceiveAppendEntriesRpcFollower() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setTerm(1);
        rpc.setLeaderId(NodeId.of("B"));
        node.onReceiveAppendEntriesRpc(new AppendEntriesRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector connector = (MockConnector) node.getContext().connector();
        AppendEntriesResult result = (AppendEntriesResult) connector.getResult();
        Assert.assertEquals(1, result.getTerm());
        Assert.assertTrue(result.isSuccess());

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(1, state.getTerm());
        Assert.assertEquals(NodeId.of("B"), state.getLeaderId());
    }

    @Test
    public void testOnReceiveAppendEntriesRpcCandidate() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.electionTimeout();
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setTerm(2);
        rpc.setLeaderId(NodeId.of("B"));
        node.onReceiveAppendEntriesRpc(new AppendEntriesRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector connector = (MockConnector) node.getContext().connector();
        AppendEntriesResult result = (AppendEntriesResult) connector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertTrue(result.isSuccess());

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(2, state.getTerm());
        Assert.assertEquals(NodeId.of("B"), state.getLeaderId());
    }

    @Test
    public void testOnReceiveAppendEntriesRpcLeader() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.electionTimeout();
        node.onReceiveRequestVoteResult(new RequestVoteResult(2, true));
        AppendEntriesRpc rpc = new AppendEntriesRpc();
        rpc.setTerm(2);
        rpc.setLeaderId(NodeId.of("B"));
        node.onReceiveAppendEntriesRpc(new AppendEntriesRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector connector = (MockConnector) node.getContext().connector();
        AppendEntriesResult result = (AppendEntriesResult) connector.getResult();
        Assert.assertEquals(2, result.getTerm());
        Assert.assertFalse(result.isSuccess());

        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.LEADER, state.getRoleName());
        Assert.assertEquals(2, state.getTerm());
    }

    @Test
    public void testOnReceiveAppendEntriesResultPeerCatchUp() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        ReplicatingState replicatingState = node.getContext().group().findReplicationState(NodeId.of("B"));
        replicatingState.startReplicating();
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("B"), createAppendEntriesRpc(1)));
        Assert.assertFalse(replicatingState.isReplicating());
        Assert.assertEquals(1, replicatingState.getMatchIndex());
    }

    @Test
    public void testOnReceiveAppendEntriesResultPeerNotCatchUp() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335)
        ).build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        ReplicatingState replicatingState = node.getContext().group().findReplicationState(NodeId.of("B"));
        replicatingState.startReplicating();
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("B"), createAppendEntriesRpc(0)));
        Assert.assertTrue(replicatingState.isReplicating());
        Assert.assertEquals(0, replicatingState.getMatchIndex());
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        Assert.assertEquals(2, mockConnector.getMessageCount());
        MockConnector.Message message = mockConnector.getLastMessage();
        Assert.assertTrue(message.getRpc() instanceof AppendEntriesRpc);
        Assert.assertEquals(NodeId.of("B"), message.getDestinationNodeId());
    }

    @Test
    public void testOnReceiveAppendEntriesResultBackOff() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.getContext().log().appendEntry(1);
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(2, true)); // become leader
        ReplicatingState replicatingState = node.getContext().group().findReplicationState(NodeId.of("B"));
        replicatingState.startReplicating();
        Assert.assertEquals(2, replicatingState.getNextIndex());
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, false),
                NodeId.of("B"), createAppendEntriesRpc(1)));
        Assert.assertTrue(replicatingState.isReplicating());
        Assert.assertEquals(1, replicatingState.getNextIndex());
        Assert.assertEquals(0, replicatingState.getMatchIndex());
    }

    @Test
    public void testOnReceiveAppendEntriesResultBackOffFailed() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        ReplicatingState replicatingState = node.getContext().group().findReplicationState(NodeId.of("B"));
        replicatingState.startReplicating();
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, false),
                NodeId.of("B"), createAppendEntriesRpc(1)));
        Assert.assertFalse(replicatingState.isReplicating());
        Assert.assertEquals(0, replicatingState.getMatchIndex());
    }

    @Test
    public void testOnReceiveAppendEntriesResultLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 3, false),
                NodeId.of("B"), createAppendEntriesRpc(1)));
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(3, state.getTerm());
    }

    @Test
    public void testOnReceiveAppendEntriesResultUnexpectedSourceNodeId() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, false),
                NodeId.of("D"), createAppendEntriesRpc(1)));
    }

    @Test
    public void testOnReceiveAppendEntriesResultNodeRemoving() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        NodeGroup.NodeState nodeState = node.getContext().group().addNode(new NodeEndpoint("D", "localhost", 2336), 2,0, false);
        nodeState.getReplicatingState().startReplicating();
        nodeState.setRemoving(true);
        node.onReceiveAppendEntriesResult(new AppendEntriesResultMessage(
                new AppendEntriesResult("", 1, true),
                NodeId.of("D"), createAppendEntriesRpc(1)));
        Assert.assertFalse(nodeState.getReplicatingState().isReplicating());
    }

    @Test
    public void testOnReceiveInstallSnapshotRpcSmallerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(3, null))
                .build();
        node.start();
        InstallSnapshotRpc rpc = new InstallSnapshotRpc();
        rpc.setTerm(2);
        node.onReceiveInstallSnapshotRpc(new InstallSnapshotRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        InstallSnapshotResult result = (InstallSnapshotResult) mockConnector.getResult();
        Assert.assertEquals(3, result.getTerm());
    }

    @Test
    public void testOnReceiveInstallSnapshotRpc() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        InstallSnapshotRpc rpc = new InstallSnapshotRpc();
        rpc.setTerm(1);
        rpc.setLastIncludedTerm(1);
        rpc.setLastIncludedIndex(2);
        rpc.setData(new byte[0]);
        rpc.setDone(true);
        node.onReceiveInstallSnapshotRpc(new InstallSnapshotRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        InstallSnapshotResult result = (InstallSnapshotResult) mockConnector.getResult();
        Assert.assertEquals(1, result.getTerm());
    }

    @Test
    public void testOnReceiveInstallSnapshotRpcLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        InstallSnapshotRpc rpc = new InstallSnapshotRpc();
        rpc.setTerm(2);
        rpc.setLeaderId(NodeId.of("B"));
        rpc.setLastIncludedTerm(1);
        rpc.setLastIncludedIndex(2);
        rpc.setData(new byte[0]);
        rpc.setDone(true);
        node.onReceiveInstallSnapshotRpc(new InstallSnapshotRpcMessage(rpc, NodeId.of("B"), null));
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        InstallSnapshotResult result = (InstallSnapshotResult) mockConnector.getResult();
        Assert.assertEquals(2, result.getTerm());
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(NodeId.of("B"), state.getLeaderId());
    }


    @Test
    public void testOnReceiveInstallSnapshotResultLargerTerm() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        node.start();
        node.onReceiveInstallSnapshotResult(new InstallSnapshotResultMessage(
                new InstallSnapshotResult(2), NodeId.of("C"), new InstallSnapshotRpc()));
        RoleState state = node.getRoleState();
        Assert.assertEquals(RoleName.FOLLOWER, state.getRoleName());
        Assert.assertEquals(2, state.getTerm());
    }

    @Test
    public void testOnReceiveInstallSnapshotResultDone() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        node.start();
        node.electionTimeout();
        mockConnector.clearMessage();
        node.onReceiveRequestVoteResult(new RequestVoteResult(2, true));
        InstallSnapshotRpc installSnapshotRpc = new InstallSnapshotRpc();
        installSnapshotRpc.setDone(true);
        node.onReceiveInstallSnapshotResult(new InstallSnapshotResultMessage(
                new InstallSnapshotResult(2), NodeId.of("C"), installSnapshotRpc));
        Assert.assertEquals(NodeId.of("C"), mockConnector.getDestinationNodeId());
        Assert.assertTrue(mockConnector.getRpc() instanceof AppendEntriesRpc);
    }

    @Test
    public void testOnReceiveInstallSnapshotResult() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .setStore(new MemoryNodeStore(1, null))
                .build();
        MockConnector mockConnector = (MockConnector) node.getContext().connector();
        node.start();
        node.electionTimeout();
        mockConnector.clearMessage();
        node.onReceiveRequestVoteResult(new RequestVoteResult(2, true));
        InstallSnapshotRpc installSnapshotRpc = new InstallSnapshotRpc();
        installSnapshotRpc.setDone(false);
        installSnapshotRpc.setData(new byte[0]);
        node.onReceiveInstallSnapshotResult(new InstallSnapshotResultMessage(
                new InstallSnapshotResult(2), NodeId.of("C"), installSnapshotRpc));
        Assert.assertEquals(NodeId.of("C"), mockConnector.getDestinationNodeId());
        Assert.assertTrue(mockConnector.getRpc() instanceof InstallSnapshotRpc);
    }

    @Test
    public void testOnGroupConfigEntryFromLeaderAppend() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        GroupConfigEntry groupConfigEntry = new AddNodeEntry(1, 1,
                node.getContext().group().getNodeEndpointsOfMajor(),
                new NodeEndpoint("D", "localhost", 2336));
        node.onGroupConfigEntryFromLeaderAppend(new GroupConfigEntryFromLeaderAppendEvent(groupConfigEntry));
        Assert.assertEquals(4, node.getContext().group().getCountOfMajor());
    }

    @Test
    public void testOnGroupConfigEntryCommittedAddNode() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        node.electionTimeout(); // become candidate
        node.onReceiveRequestVoteResult(new RequestVoteResult(1, true)); // become leader
        AddNodeEntry groupConfigEntry = new AddNodeEntry(2, 1,
                node.getContext().group().getNodeEndpointsOfMajor(),
                new NodeEndpoint("D", "localhost", 2336));
        node.onGroupConfigEntryCommitted(new GroupConfigEntryCommittedEvent(groupConfigEntry));
    }

    @Test
    public void testOnGroupConfigEntryBatchRemoved() {
        NodeImpl node = (NodeImpl) newNodeBuilder(
                NodeId.of("A"),
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335))
                .build();
        node.start();
        GroupConfigEntry groupConfigEntry = new RemoveNodeEntry(1, 1, ImmutableSet.of(
                new NodeEndpoint("A", "localhost", 2333),
                new NodeEndpoint("B", "localhost", 2334),
                new NodeEndpoint("C", "localhost", 2335),
                new NodeEndpoint("D", "localhost", 2336)
        ), NodeId.of("D"));
        node.onGroupConfigEntryBatchRemoved(new GroupConfigEntryBatchRemovedEvent(groupConfigEntry));
        Assert.assertEquals(4, node.getContext().group().getCountOfMajor());
    }

    @AfterClass
    public static void afterClass() throws Exception {
        taskExecutor.shutdown();
        groupConfigChangeTaskExecutor.shutdown();
    }

}