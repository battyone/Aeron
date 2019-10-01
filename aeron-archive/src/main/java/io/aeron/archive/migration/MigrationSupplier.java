/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a series of migration steps given a starting semantic version.
 *
 * Migration steps are stored statically in a list sorted by order of operation. Each has
 * a minimum version. The first step that has a minimum version greater than the passed in version forms
 * the start of the migration steps. All steps afterward are included in the migration.
 *
 * A step need not be a complete operation. A series of operations may be broken down in steps and
 * included with the same minimum version.
 */
public class MigrationSupplier
{
    private static final ArrayList<MigrationStep> ALL_MIGRATION_STEPS = new ArrayList<>();

    static
    {
        ALL_MIGRATION_STEPS.add(new Migration0to1());
        // as migrations are added, they are added to the static list in order of operation
    }

    public static List<MigrationStep> createMigration(final int version)
    {
        final List<MigrationStep> steps = new ArrayList<>();

        for (int i = 0; i < ALL_MIGRATION_STEPS.size(); i++)
        {
            if (ALL_MIGRATION_STEPS.get(i).minimumVersion() > version)
            {
                steps.addAll(ALL_MIGRATION_STEPS.subList(i, ALL_MIGRATION_STEPS.size() - 1));
                break;
            }
        }

        return steps;
    }
}