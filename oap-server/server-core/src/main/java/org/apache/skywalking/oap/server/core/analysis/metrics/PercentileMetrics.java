/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.core.analysis.metrics;

import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.Arg;
import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.Entrance;
import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.MetricsFunction;
import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.SourceFrom;
import org.apache.skywalking.oap.server.core.storage.annotation.BanyanDB;
import org.apache.skywalking.oap.server.core.storage.annotation.Column;
import org.apache.skywalking.oap.server.core.storage.annotation.ElasticSearch;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.Setter;

/**
 * Percentile is a better implementation than deprecated PxxMetrics in older releases.
 * This could calculate the multiple P50/75/90/95/99 values once for all.
 *
 * @since 7.0.0
 * Deprecated since 10.0.0, use {@link PercentileMetrics2} instead.
 */
@Deprecated
@MetricsFunction(functionName = "percentile")
public abstract class PercentileMetrics extends Metrics implements MultiIntValuesHolder {
    protected static final String DATASET = "dataset";
    protected static final String VALUE = "datatable_value";
    protected static final String PRECISION = "precision";

    private static final int[] RANKS = {
        50,
        75,
        90,
        95,
        99
    };

    @Getter
    @Setter
    @Column(name = VALUE, dataType = Column.ValueDataType.LABELED_VALUE, storageOnly = true, multiIntValues = true)
    @ElasticSearch.Column(legacyName = "value")
    @BanyanDB.MeasureField
    private DataTable percentileValues;
    @Getter
    @Setter
    @Column(name = PRECISION, storageOnly = true)
    @BanyanDB.MeasureField
    private int precision;
    @Getter
    @Setter
    @Column(name = DATASET, storageOnly = true)
    @BanyanDB.MeasureField
    private DataTable dataset;

    private boolean isCalculated;

    public PercentileMetrics() {
        percentileValues = new DataTable(RANKS.length);
        dataset = new DataTable(30);
    }

    @Entrance
    public final void combine(@SourceFrom int value, @Arg int precision) {
        this.isCalculated = false;
        this.precision = precision;

        String index = String.valueOf(value / precision);
        dataset.valueAccumulation(index, 1L);
    }

    @Override
    public boolean combine(Metrics metrics) {
        this.isCalculated = false;

        PercentileMetrics percentileMetrics = (PercentileMetrics) metrics;
        this.dataset.append(percentileMetrics.dataset);
        return true;
    }

    @Override
    public final void calculate() {
        if (!isCalculated) {
            long total = dataset.sumOfValues();

            int[] roofs = new int[RANKS.length];
            for (int i = 0; i < RANKS.length; i++) {
                roofs[i] = Math.round(total * RANKS[i] * 1.0f / 100);
            }

            long count = 0;
            final List<String> sortedKeys = dataset.sortedKeys(Comparator.comparingInt(Integer::parseInt));

            int loopIndex = 0;
            for (String key : sortedKeys) {
                final Long value = dataset.get(key);

                count += value;
                for (int rankIdx = loopIndex; rankIdx < roofs.length; rankIdx++) {
                    int roof = roofs[rankIdx];

                    if (count >= roof) {
                        percentileValues.put(String.valueOf(rankIdx), Long.parseLong(key) * precision);
                        loopIndex++;
                    } else {
                        break;
                    }
                }
            }
        }
    }

    @Override
    public int[] getValues() {
        return percentileValues.sortedValues(Comparator.comparingInt(Integer::parseInt))
                               .stream()
                               .flatMapToInt(l -> IntStream.of(l.intValue()))
                               .toArray();
    }
}
