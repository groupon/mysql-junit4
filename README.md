# JUnit 4 support for Tests against MySQL

This library provides support classes for writing JUnit test against
an embedded MySQL instance. The conventional way to use it makes use
of [JUnit 4 Rules](https://github.com/junit-team/junit/wiki/Rules). It
basically creates an instance of MySQL, on demand, for testing
purposes. It then creates connections to the MySQL database using the
standard library (driver) for doing so.

It has additional explicit (optional) support for
[jdbi](http://jdbi.org/) and [flyway](http://flywaydb.org/) based
fixtures.

```java
    /**
     * A ClassRule will be executed before any tests in the class are run,
     * and after all of them are finished.
     */
    @ClassRule public static MySQLRule mysql = MySQLRule.global();

    /**
     * setup() will be run before each test method, and tearDown after
     * each test method.
     */
    @Rule public MySQLRule.Fixtures fixtures = mysql.createFixtures(new JDBIFixture()
    {
        @Override
        protected void before(final Handle handle)
        {
            handle.execute("create table something ( id int primary key, name varchar(255) )");
            PreparedBatch batch = handle.prepareBatch("insert into something (id, name) values (:id, :name)");
            batch.bind("id", 1).bind("name", "Gene").add();
            batch.bind("id", 2).bind("name", "Brian").add();
            batch.execute();
        }

        @Override
        protected void after(final Handle handle)
        {
            handle.execute("drop table something");
        }
    });
 ```

This library uses
[Connector/MXJ](http://dev.mysql.com/doc/connector-mxj/en/connector-mxj.html)
to embed MySQL.

# Examples

Take a look at
[MySQLRuleExample](https://github.com/groupon/mysql-junit4/blob/master/mysql-junit4/src/test/java/com/groupon/mysql/testing/MySQLRuleExample.java)
and
[FlywayFixtureExample](https://github.com/groupon/mysql-junit4/blob/master/mysql-junit4/src/test/java/com/groupon/mysql/testing/FlywayFixtureExample.java)
for sample code using this library.

# Getting It

The easiest way is via Maven:

```xml
<dependency>
  <groupId>com.groupon.mysql</groupId>
  <artifactId>mysql-junit4</artifactId>
  <version>0.0.1</version>
</dependency>
```

# Licensing

This library is licensed under the 3-Clause BSD License. Note that it
relies on MySQL which is GPL, but if you want to test against MySQL
you should already know that!
