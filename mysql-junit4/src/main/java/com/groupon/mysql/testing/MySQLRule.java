/*
 * Copyright © 2013, Groupon, Inc
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.groupon.mysql.testing;


import org.junit.rules.ExternalResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class MySQLRule extends ExternalResource
{
    private static final AtomicReference<MySQLRule> global = new AtomicReference<>();


    private final ReentrantLock lock = new ReentrantLock(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<EmbeddedMySQL> daemon = new AtomicReference<>();
    private final boolean shutdownOnAfter;


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
        return daemon.get().getDataSource(database);
    }

    private void _start() throws Exception
    {
        daemon.set(EmbeddedMySQL.start());
    }

    private void _stop() throws InterruptedException, IOException
    {
        daemon.get().close();
        daemon.set(null);
        started.set(false);
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
        return new Fixtures(daemon.get().getDataSource(database), f);
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
     * A resource to be used for setting up fixtures on a Rule or ClassRule.
     *
     * @see MySQLRule#createFixtures(com.groupon.mysql.testing.MySQLRule.Fixture)
     */
    public static final class Fixtures extends ExternalResource
    {
        private final DataSource ds;
        private final List<Fixture> fixtures;

        private Fixtures(final DataSource ds, final Fixture... fixture)
        {
            this.ds = ds;
            this.fixtures = Arrays.asList(fixture);
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
}
