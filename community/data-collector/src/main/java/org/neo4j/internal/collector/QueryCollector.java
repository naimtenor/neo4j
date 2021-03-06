/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.collector;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.neo4j.kernel.api.exceptions.InvalidArgumentsException;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.api.query.QuerySnapshot;
import org.neo4j.kernel.impl.query.QueryExecutionMonitor;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobScheduler;

/**
 * Simple Thread-safe query collector.
 *
 * Note that is has several potentially not-so-nice properties:
 *
 *  - It buffers all query data until collection is done. On high-workload systems
 *    this could use substantial memory
 *
 *  - All threads that report queries on {@link QueryCollector#endSuccess(org.neo4j.kernel.api.query.ExecutingQuery)}
 *    contend for writing to the queue, which might cause delays before the first result on highly concurrent systems
 */
class QueryCollector extends CollectorStateMachine<Iterator<QuerySnapshot>> implements QueryExecutionMonitor
{
    private volatile boolean isCollecting;
    private final ConcurrentLinkedQueue<QuerySnapshot> queries;
    private final JobScheduler jobScheduler;

    QueryCollector( JobScheduler jobScheduler )
    {
        this.jobScheduler = jobScheduler;
        isCollecting = false;
        queries = new ConcurrentLinkedQueue<>();
    }

    // CollectorStateMachine

    @Override
    Result doCollect( Map<String,Object> config, long collectionId ) throws InvalidArgumentsException
    {
        int collectSeconds = QueryCollectorConfig.of( config ).collectSeconds;
        jobScheduler.schedule( Group.DATA_COLLECTOR, () -> QueryCollector.this.stop( collectionId ), collectSeconds, TimeUnit.SECONDS );
        isCollecting = true;
        return success( "Collection started." );
    }

    @Override
    Result doStop()
    {
        isCollecting = false;
        return success( "Collection stopped." );
    }

    @Override
    Result doClear()
    {
        queries.clear();
        return success( "Data cleared." );
    }

    @Override
    Iterator<QuerySnapshot> doGetData()
    {
        return queries.iterator();
    }

    // QueryExecutionMonitor

    @Override
    public void endFailure( ExecutingQuery query, Throwable failure )
    {
    }

    @Override
    public void endSuccess( ExecutingQuery query )
    {
        if ( isCollecting )
        {
            queries.add( query.snapshot() );
        }
    }
}
