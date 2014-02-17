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


import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.StringMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MultipleFixturesExample
{
    @ClassRule public static MySQLRule mysql = MySQLRule.global();

    /**
     * You can add multiple fixtures to a Fixtures :-)
     */
    @Rule public MySQLRule.Fixtures fixtures = mysql.createFixtures(new FlywayFixture("db/migration", "db/fixtures"),
                                                                    new JDBIFixture()
                                                                    {
                                                                        @Override
                                                                        protected void before(final Handle handle)
                                                                        {
                                                                            handle.execute("insert into something (id, name) values (3, 'Peter')");
                                                                        }
                                                                    });

    @Test
    public void testFoo() throws Exception
    {
        try (Handle h = DBI.open(mysql.getDataSource())) {
            List<String> names = h.createQuery("select name from something order by id")
                                  .map(StringMapper.FIRST)
                                  .list();
            assertThat(names).containsExactly("Gene", "Brian", "Peter");
        }
    }

}
