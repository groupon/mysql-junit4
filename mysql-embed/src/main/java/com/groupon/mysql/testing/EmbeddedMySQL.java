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
import com.google.common.io.Files;
import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import com.mysql.management.MysqldResource;
import com.mysql.management.MysqldResourceI;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

public class EmbeddedMySQL implements Closeable
{
    private static final AtomicInteger internalCounter = new AtomicInteger(4096);
    private final MysqldResource mysqld;
    private final File dataDir;
    private final int port;

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

    private String getUrl(String database)
    {
        return "jdbc:mysql://localhost:" + port + "/" + database + "?createDatabaseIfNotExist=true";
    }

    private EmbeddedMySQL() throws InterruptedException
    {
        port = AvailablePortFinder.getNextAvailable(internalCounter.getAndIncrement());

        dataDir = Files.createTempDir();

        mysqld = new MysqldResource(dataDir);

        Map<String, String> props = ImmutableMap.of(MysqldResourceI.PORT, Integer.toString(port),
                                                    MysqldResourceI.INITIALIZE_USER, "true",
                                                    MysqldResourceI.INITIALIZE_USER_NAME, "user",
                                                    MysqldResourceI.INITIALIZE_PASSWORD, "pass",
                                                    "default-time-zone", "+00:00");
        mysqld.start("mysql-daemon", props);
        if (!mysqld.isRunning()) {
            throw new IllegalStateException("MySQL failed to start");
        }
        while (!mysqld.isReadyForConnections()) { Thread.sleep(100); }
    }

    public static EmbeddedMySQL start() throws InterruptedException
    {
        return new EmbeddedMySQL();
    }

    @Override
    public void close() throws IOException
    {
        dataSources.invalidateAll();
        dataSources.cleanUp();
        mysqld.shutdown();
        try
        {
            while (mysqld.isRunning()) { Thread.sleep(100); }
            Runtime.getRuntime().exec("rm -rf " + dataDir.getAbsolutePath()).waitFor();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

    }

    public DataSource getDataSource(String database)
    {
        return dataSources.getUnchecked(database);
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
         * @throws java.util.NoSuchElementException if there are no ports available
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
            try
            {
                ss = new ServerSocket(port);
                ss.setReuseAddress(true);
                ds = new DatagramSocket(port);
                ds.setReuseAddress(true);
                return true;
            }
            catch (IOException e)
            {
            }
            finally
            {
                if (ds != null) {
                    ds.close();
                }

                if (ss != null) {
                    try
                    {
                        ss.close();
                    }
                    catch (IOException e)
                    {
                    /* should not be thrown */
                    }
                }
            }

            return false;
        }
    }
}
