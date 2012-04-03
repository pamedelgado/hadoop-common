/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.junit.Assert.*;

import java.io.File;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ha.NodeFencer;
import org.apache.hadoop.ha.ZKFailoverController;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.ha.TestNodeFencer.AlwaysSucceedFencer;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.tools.DFSZKFailoverController;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MultithreadedTestUtil.TestContext;
import org.apache.hadoop.test.MultithreadedTestUtil.TestingThread;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


import com.google.common.base.Supplier;

public class TestDFSZKFailoverController extends ClientBase {
  private Configuration conf;
  private MiniDFSCluster cluster;
  private TestContext ctx;
  private ZKFCThread thr1, thr2;
  private FileSystem fs;
  
  @Override
  public void setUp() throws Exception {
    // build.test.dir is used by zookeeper
    new File(System.getProperty("build.test.dir", "build")).mkdirs();
    super.setUp();
  }

  @Before
  public void setup() throws Exception {
    conf = new Configuration();
    conf.set(ZKFailoverController.ZK_QUORUM_KEY, hostPort);
    conf.set(NodeFencer.CONF_METHODS_KEY,
        AlwaysSucceedFencer.class.getName());

    MiniDFSNNTopology topology = new MiniDFSNNTopology()
    .addNameservice(new MiniDFSNNTopology.NSConf("ns1")
        .addNN(new MiniDFSNNTopology.NNConf("nn1").setIpcPort(10001))
        .addNN(new MiniDFSNNTopology.NNConf("nn2").setIpcPort(10002)));
    cluster = new MiniDFSCluster.Builder(conf)
        .nnTopology(topology)
        .numDataNodes(0)
        .build();
    cluster.waitActive();

    ctx = new TestContext();
    ctx.addThread(thr1 = new ZKFCThread(ctx, 0));
    assertEquals(0, thr1.zkfc.run(new String[]{"-formatZK"}));

    thr1.start();
    waitForHAState(0, HAServiceState.ACTIVE);
    
    ctx.addThread(thr2 = new ZKFCThread(ctx, 1));
    thr2.start();
    
    fs = HATestUtil.configureFailoverFs(cluster, conf);
  }
  
  @After
  public void shutdown() throws Exception {
    cluster.shutdown();
    
    if (thr1 != null) {
      thr1.interrupt();
    }
    if (thr2 != null) {
      thr2.interrupt();
    }
    if (ctx != null) {
      ctx.stop();
    }
  }
  
  /**
   * Test that automatic failover is triggered by shutting the
   * active NN down.
   */
  @Test(timeout=30000)
  public void testFailoverAndBackOnNNShutdown() throws Exception {
    Path p1 = new Path("/dir1");
    Path p2 = new Path("/dir2");
    
    // Write some data on the first NN
    fs.mkdirs(p1);
    // Shut it down, causing automatic failover
    cluster.shutdownNameNode(0);
    // Data should still exist. Write some on the new NN
    assertTrue(fs.exists(p1));
    fs.mkdirs(p2);
    assertEquals(AlwaysSucceedFencer.getLastFencedService().getAddress(),
        thr1.zkfc.getLocalTarget().getAddress());
    
    // Start the first node back up
    cluster.restartNameNode(0);
    // This should have no effect -- the new node should be STANDBY.
    waitForHAState(0, HAServiceState.STANDBY);
    assertTrue(fs.exists(p1));
    assertTrue(fs.exists(p2));
    // Shut down the second node, which should failback to the first
    cluster.shutdownNameNode(1);
    waitForHAState(0, HAServiceState.ACTIVE);

    // First node should see what was written on the second node while it was down.
    assertTrue(fs.exists(p1));
    assertTrue(fs.exists(p2));
    assertEquals(AlwaysSucceedFencer.getLastFencedService().getAddress(),
        thr2.zkfc.getLocalTarget().getAddress());
  }
  
  private void waitForHAState(int nnidx, final HAServiceState state)
      throws TimeoutException, InterruptedException {
    final NameNode nn = cluster.getNameNode(nnidx);
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        try {
          return nn.getRpcServer().getServiceStatus().getState() == state;
        } catch (Exception e) {
          e.printStackTrace();
          return false;
        }
      }
    }, 50, 5000);
  }

  /**
   * Test-thread which runs a ZK Failover Controller corresponding
   * to a given NameNode in the minicluster.
   */
  private class ZKFCThread extends TestingThread {
    private final DFSZKFailoverController zkfc;

    public ZKFCThread(TestContext ctx, int idx) {
      super(ctx);
      this.zkfc = new DFSZKFailoverController();
      zkfc.setConf(cluster.getConfiguration(idx));
    }

    @Override
    public void doWork() throws Exception {
      try {
        assertEquals(0, zkfc.run(new String[0]));
      } catch (InterruptedException ie) {
        // Interrupted by main thread, that's OK.
      }
    }
  }

}