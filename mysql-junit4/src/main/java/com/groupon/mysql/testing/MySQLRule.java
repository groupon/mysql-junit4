package com.groupon.mysql.testing;

/*
 * Copyright (c) 2013, Groupon, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * Neither the name of GROUPON nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import com.mysql.management.MysqldResource;
import com.mysql.management.MysqldResourceI;
import org.junit.rules.ExternalResource;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class MySQLRule extends ExternalResource
{
    private static final AtomicReference<MySQLRule> global = new AtomicReference<>();
    /**
     * Shouldn't be needed, but failed tests can leave mysql hanging :-( Need a finally()
     * in JUnit -bmc
     */
    private static final AtomicInteger internalCounter = new AtomicInteger(4096);




    private final ReentrantLock lock = new ReentrantLock(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger port = new AtomicInteger(-1);
    private final AtomicReference<MysqldResource> daemon = new AtomicReference<>();
    private final AtomicReference<File> dir = new AtomicReference<>();
    private final boolean shutdownOnAfter;
    private final LoadingCache<String, DataSource> dataSources = CacheBuilder.newBuilder().build(new CacheLoader<String, DataSource>()
    {
        @Override
        public DataSource load(final String key) throws Exception
        {
            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(getUrl(key));
            ds.setUser("user");
            ds.setPassword("pass");
            return ds;
        }
    });


    private MySQLRule(boolean shutdownOnAfter)
    {
        this.shutdownOnAfter = shutdownOnAfter;
    }

    /**
     * Obtain a global MySQLRule instance which will only be started once per JVM. This is recommended
     * if you care about how long your tests take to run.
     */
    public static MySQLRule global()
    {
        MySQLRule mysql = new MySQLRule(false);
        if (global.compareAndSet(null, mysql)) {
            return mysql;
        }
        return global.get();
    }

    /**
     * Obtain a MySQLRule which will start up and shutdown normally based on ClassRule or Rule
     */
    public static MySQLRule oneOff()
    {
        return new MySQLRule(true);
    }

    @Override
    protected void before() throws Exception
    {
        lock.lock();
        try {
            if (started.get()) {
                return;
            }
            _start();

            // always register the shutdown hook, after() isn't called if the test fails
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        _stop();
                    }
                    catch (Exception e) {
                        System.err.printf("unable to shut down mysql cleanly: %s\n", e);
                    }
                }
            }));
            started.set(true);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    protected void after()
    {
        if (shutdownOnAfter) {

            lock.lock();
            try {
                if (!started.get()) {
                    return;
                }
                try {
                    _stop();
                }
                catch (Exception e) {
                    throw new RuntimeException("Unable to shut down MySQL", e);
                }
                started.set(false);
            }
            finally {
                lock.unlock();
            }
        }
    }

    /**
     * Obtain a datasource for a database where you don't care which one it is. This will return
     * a datasource against the same database each time.
     */
    public DataSource getDataSource() throws Exception
    {
        return getDataSource("foo");
    }

    /**
     * Obtain a datasource against a particularly named database
     */
    public DataSource getDataSource(String database) throws Exception
    {
        if (!started.get()) {
            throw new IllegalStateException("mysql has not been started");
        }
        return dataSources.get(database);
    }

    private void _start() throws Exception
    {

        port.set(AvailablePortFinder.getNextAvailable(internalCounter.getAndIncrement()));

        File tmp_dir = Files.createTempDir();

        MysqldResource mysqld = new MysqldResource(tmp_dir);

        Map<String, String> props = ImmutableMap.of(MysqldResourceI.PORT, Integer.toString(port.get()),
                                                    MysqldResourceI.INITIALIZE_USER, "true",
                                                    MysqldResourceI.INITIALIZE_USER_NAME, "user",
                                                    MysqldResourceI.INITIALIZE_PASSWORD, "pass",
                                                    "default-time-zone", "+00:00");
        mysqld.start("mysql-daemon", props);
        if (!mysqld.isRunning()) {
            throw new IllegalStateException("MySQL failed to start");
        }
        while (!mysqld.isReadyForConnections()) { Thread.sleep(100); }

        daemon.set(mysqld);
        dir.set(tmp_dir);
    }

    private void _stop() throws InterruptedException, IOException
    {
        MysqldResource mysqld = daemon.get();
        File tmp_dir = dir.get();

        mysqld.shutdown();
        while (mysqld.isRunning()) { Thread.sleep(100); }
        Runtime.getRuntime().exec("rm -rf " + tmp_dir.getAbsolutePath()).waitFor();

        dataSources.invalidateAll();
        dir.set(null);
        daemon.set(null);

    }

    /**
     * Obtain a MySQL.Fixtures instance which itself should be stored on a Rule or ClassRule member variable
     * in order to have the fixture setup and tearDown called before and after each test method (or class).
     *
     * @param database The database name {@link MySQLRule#getDataSource(String)}
     * @param f        A callback to set up and tear down fixtures.
     */
    public Fixtures createFixtures(String database, Fixture... f)
    {
        return new Fixtures(dataSources.getUnchecked(database), f);
    }

    /**
     * Obtain a MySQL.Fixtures instance which itself should be stored on a Rule or ClassRule member variable
     * in order to have the fixture setup and tearDown called before and after each test method (or class).
     * <p/>
     * Sets up fixtures against the same database as is obtained from {@link MySQLRule#getDataSource()}
     *
     * @param f A callback to set up and tear down fixtures.
     */
    public Fixtures createFixtures(Fixture... f)
    {
        return createFixtures("foo", f);
    }

    /**
     * Obtain the JDBC connection URL
     *
     * @param database The database name
     */
    public String getUrl(String database)
    {
        return "jdbc:mysql://localhost:" + port.get() + "/" + database + "?createDatabaseIfNotExist=true";
    }

    /**
     * Obtain the JDBC connection URL for the default database
     */
    public String getUrl()
    {
        return getUrl("foo");
    }

    /**
     * Obtain a valid user with all priviledges
     */
    public String getUser()
    {
        return "user";
    }

    /**
     * Obtain the password for the user in {@link com.groupon.mysql.testing.MySQLRule#getUser()}  }
     */
    public String getPass()
    {
        return "pass";
    }

    /**
     * A resource to be used for setting up fixtures on a Rule or ClassRule.
     *
     * @see MySQLRule#createFixtures(com.groupon.mysql.testing.MySQLRule.Fixture)
     */
    public final static class Fixtures extends ExternalResource
    {
        private final DataSource ds;
        private final List<Fixture> fixtures;

        private Fixtures(final DataSource ds, final Fixture... fixture)
        {
            this.ds = ds;
            this.fixtures = Lists.newArrayList(fixture);
        }

        @Override
        protected void before() throws Throwable
        {
            for (Fixture fixture : fixtures) {
                fixture.setup(ds);
            }
        }

        @Override
        protected void after()
        {
            List<Exception> exceptions = Lists.newArrayList();
            for (Fixture fixture : fixtures) {
                try {
                    fixture.tearDown(ds);
                }
                catch (Exception e) {
                    throw new RuntimeException("unable to clean up fixtures", e);
                }
            }

        }
    }

    /**
     * Class which allows setting up and tearing down fixtures.
     *
     * @see MySQLRule#createFixtures(com.groupon.mysql.testing.MySQLRule.Fixture)
     */
    public static class Fixture
    {
        /**
         * Called before, should set up any fixtures needed for tests.
         */
        protected void setup(DataSource ds) throws Exception
        {}

        /**
         * Called after, should clean up after tests.
         */
        protected void tearDown(DataSource ds) throws Exception
        {}
    }

 /*
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

    /**
     * Finds currently available server ports.
     *
     * @author The Apache Directory Project (mina-dev@directory.apache.org)
     * @version $Rev$
     * @see <a href="http://www.iana.org/assignments/port-numbers">IANA.org</a>
     */
    private static class AvailablePortFinder
    {
        /**
         * The minimum number of server port number.
         */
        public static final int MIN_PORT_NUMBER = 1;
        /**
         * The maximum number of server port number.
         */
        public static final int MAX_PORT_NUMBER = 49151;

        /**
         * Gets the next available port starting at a port.
         *
         * @param fromPort the port to scan for availability
         *
         * @throws java.util.NoSuchElementException
         *          if there are no ports available
         */
        private static int getNextAvailable(int fromPort)
        {
            if ((fromPort < MIN_PORT_NUMBER) || (fromPort > MAX_PORT_NUMBER)) {
                throw new IllegalArgumentException("Invalid start port: "
                                                   + fromPort);
            }

            for (int i = fromPort; i <= MAX_PORT_NUMBER; i++) {
                if (available(i)) {
                    return i;
                }
            }

            throw new NoSuchElementException("Could not find an available port "
                                             + "above " + fromPort);
        }

        /**
         * Checks to see if a specific port is available.
         *
         * @param port the port to check for availability
         */
        private static boolean available(int port)
        {
            if ((port < MIN_PORT_NUMBER) || (port > MAX_PORT_NUMBER)) {
                throw new IllegalArgumentException("Invalid start port: " + port);
            }

            ServerSocket ss = null;
            DatagramSocket ds = null;
            try {
                ss = new ServerSocket(port);
                ss.setReuseAddress(true);
                ds = new DatagramSocket(port);
                ds.setReuseAddress(true);
                return true;
            }
            catch (IOException e) {
            }
            finally {
                if (ds != null) {
                    ds.close();
                }

                if (ss != null) {
                    try {
                        ss.close();
                    }
                    catch (IOException e) {
                    /* should not be thrown */
                    }
                }
            }

            return false;
        }
    }
}
