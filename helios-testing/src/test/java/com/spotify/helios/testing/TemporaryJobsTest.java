package com.spotify.helios.testing;

import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.system.SystemTestBase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.net.Socket;
import java.util.Map;

import static com.google.common.base.Charsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.experimental.results.PrintableResult.testResult;
import static org.junit.experimental.results.ResultMatchers.hasFailureContaining;
import static org.junit.experimental.results.ResultMatchers.isSuccessful;

public class TemporaryJobsTest extends SystemTestBase {

  // These static fields exist as a way for nested tests to access non-static fields and methods in
  // SystemTestBase. This is a bit ugly, but we can't pass the values to FakeTest, because we don't
  // instantiate it, JUnit does in the PrintableResult.testResult method. And since JUnit
  // instantiates it, it must be a static class, which means it can't access the non-static fields
  // in SystemTestBase.
  private static HeliosClient client;
  private static String testHost;

  private static final class TestProber extends DefaultProber {

    @Override
    public boolean probe(final String host, final int port) {
      // Probe for ports where docker is running instead of on the mock testHost address
      assertEquals(testHost, host);
      return super.probe(DOCKER_ADDRESS, port);
    }
  }

  public static class SimpleTest {

    @Rule
    public final TemporaryJobs temporaryJobs = new TemporaryJobs(client, new TestProber());

    private TemporaryJob job1;

    @Before
    public void setup() {
      job1 = temporaryJobs.job()
          .image("ubuntu:12.04")
          .command("sh", "-c", "apt-get install nmap -qq --force-yes && " +
                               "while :; do ncat -e /bin/cat -k -l 4711; done")
          .port("echo", 4711)
          .deploy(testHost);
    }

    @Test
    public void testDeployment() throws Exception {
      // Verify that it is possible to deploy additional jobs during test
      temporaryJobs.job()
          .image("ubuntu:12.04")
          .command("sh", "-c", "while :; do sleep 5; done")
          .host(testHost)
          .deploy();

      final Map<JobId, Job> jobs = client.jobs().get(15, SECONDS);
      assertEquals("wrong number of jobs running", 2, jobs.size());
      for (Job job : jobs.values()) {
        assertEquals("wrong job running", "ubuntu:12.04", job.getImage());
      }

      ping(DOCKER_ADDRESS, job1.port(testHost, "echo"));
    }

    private void ping(final String host, final int port) throws Exception {
      try (final Socket s = new Socket(host, port)) {
        byte[] ping = "ping".getBytes(UTF_8);
        s.getOutputStream().write(ping);
        final byte[] pong = new byte[4];
        final int n = s.getInputStream().read(pong);
        assertEquals(4, n);
        assertArrayEquals(ping, pong);
      }
    }
  }

  public static class BadTest {

    @Rule
    public final TemporaryJobs temporaryJobs = new TemporaryJobs(client, new TestProber());

    private TemporaryJob job2 = temporaryJobs.job()
        .image("base")
        .deploy(testHost);

    @Test
    public void testFail() throws Exception {
      fail();
    }
  }

  @Test
  public void testRule() throws Exception {
    startDefaultMaster();
    client = defaultClient();
    testHost = getTestHost();
    startDefaultAgent(testHost);

    assertThat(testResult(SimpleTest.class), isSuccessful());
    assertTrue("jobs are running that should not be",
               client.jobs().get(15, SECONDS).isEmpty());
  }

  @Test
  public void verifyJobFailsWhenCalledBeforeTestRun() throws Exception {
    startDefaultMaster();
    client = defaultClient();
    testHost = getTestHost();
    assertThat(testResult(BadTest.class),
               hasFailureContaining("deploy() must be called in a @Before or in the test method"));
  }

}