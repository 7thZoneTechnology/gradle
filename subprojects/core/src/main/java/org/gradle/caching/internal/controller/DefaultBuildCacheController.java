/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.controller;

import com.google.common.annotations.VisibleForTesting;
import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;
import org.gradle.caching.internal.BuildCacheTempFileStore;
import org.gradle.caching.internal.controller.operations.PackOperationDetails;
import org.gradle.caching.internal.controller.operations.PackOperationResult;
import org.gradle.caching.internal.controller.operations.UnpackOperationDetails;
import org.gradle.caching.internal.controller.operations.UnpackOperationResult;
import org.gradle.caching.internal.controller.service.BaseBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.BuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.BuildCacheServiceRole;
import org.gradle.caching.internal.controller.service.BuildCacheServicesConfiguration;
import org.gradle.caching.internal.controller.service.DefaultLocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.LoadTarget;
import org.gradle.caching.internal.controller.service.LocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.NullBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.NullLocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.OpFiringBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.StoreTarget;
import org.gradle.caching.local.internal.LocalBuildCacheService;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.progress.BuildOperationDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class DefaultBuildCacheController implements BuildCacheController {

    @VisibleForTesting
    public static final int MAX_ERRORS = 3;

    @VisibleForTesting
    final BuildCacheServiceHandle legacyLocal;

    @VisibleForTesting
    final BuildCacheServiceHandle remote;

    @VisibleForTesting
    final LocalBuildCacheServiceHandle local;

    private final BuildCacheTempFileStore tmp;
    private final BuildOperationExecutor buildOperationExecutor;

    public DefaultBuildCacheController(
        BuildCacheServicesConfiguration config,
        BuildOperationExecutor buildOperationExecutor,
        BuildCacheTempFileStore tempFileStore,
        boolean logStackTraces
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.legacyLocal = toHandle(config.legacyLocal, config.legacyLocalPush, BuildCacheServiceRole.LOCAL, buildOperationExecutor, logStackTraces);
        this.remote = toHandle(config.remote, config.remotePush, BuildCacheServiceRole.REMOTE, buildOperationExecutor, logStackTraces);
        this.local = toHandle(config.local, config.localPush);
        this.tmp = tempFileStore;
    }

    @Nullable
    @Override
    public <T> T load(final BuildCacheLoadCommand<T> command) {
        final Unpack<T> unpack = new Unpack<T>(command);

        if (local.canLoad()) {
            local.load(command.getKey(), unpack);
            if (unpack.result != null) {
                return unpack.result.getMetadata();
            }
        }

        if (legacyLocal.canLoad() || remote.canLoad()) {
            tmp.allocate(command.getKey(), new Action<File>() {
                @Override
                public void execute(File file) {
                    LoadTarget loadTarget = new LoadTarget(file);
                    if (legacyLocal.canLoad()) {
                        legacyLocal.load(command.getKey(), loadTarget);
                    }

                    if (remote.canLoad() && !loadTarget.isLoaded()) {
                        remote.load(command.getKey(), loadTarget);
                    }

                    if (loadTarget.isLoaded()) {
                        unpack.execute(file);
                        if (local.canStore()) {
                            local.store(command.getKey(), file);
                        }
                    }
                }
            });
        }

        BuildCacheLoadCommand.Result<T> result = unpack.result;
        if (result == null) {
            return null;
        } else {
            return result.getMetadata();
        }
    }

    private class Unpack<T> implements Action<File> {
        private final BuildCacheLoadCommand<T> command;

        private BuildCacheLoadCommand.Result<T> result;

        private Unpack(BuildCacheLoadCommand<T> command) {
            this.command = command;
        }

        @Override
        public void execute(final File file) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) {
                    try {
                        result = command.load(new FileInputStream(file));
                    } catch (FileNotFoundException e) {
                        throw UncheckedException.throwAsUncheckedException(e);
                    }

                    context.setResult(new UnpackOperationResult(
                        result.getArtifactEntryCount()
                    ));
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Unpack " + command.getKey() + " build cache entry")
                        .details(new UnpackOperationDetails(command.getKey(), file.length()));
                }
            });
        }

    }

    @Override
    public void store(final BuildCacheStoreCommand command) {
        boolean anyStore = local.canStore() || legacyLocal.canStore() || remote.canStore();
        if (!anyStore) {
            return;
        }

        final BuildCacheKey key = command.getKey();
        final Pack pack = new Pack(command);

        tmp.allocate(command.getKey(), new Action<File>() {
            @Override
            public void execute(File file) {
                pack.execute(file);

                if (legacyLocal.canStore()) {
                    legacyLocal.store(key, new StoreTarget(file));
                }

                if (remote.canStore()) {
                    remote.store(key, new StoreTarget(file));
                }

                if (local.canStore()) {
                    local.store(key, file);
                }
            }
        });
    }

    private class Pack implements Action<File> {

        private final BuildCacheStoreCommand command;

        private Pack(BuildCacheStoreCommand command) {
            this.command = command;
        }

        @Override
        public void execute(final File file) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) {
                    try {
                        BuildCacheStoreCommand.Result result = command.store(new FileOutputStream(file));
                        context.setResult(new PackOperationResult(
                            result.getArtifactEntryCount(),
                            file.length()
                        ));
                    } catch (IOException e) {
                        throw UncheckedException.throwAsUncheckedException(e);
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Pack " + command.getKey() + " build cache entry")
                        .details(new PackOperationDetails(command.getKey()));
                }
            });
        }
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(legacyLocal, local, remote).stop();
    }

    private static BuildCacheServiceHandle toHandle(BuildCacheService service, boolean push, BuildCacheServiceRole role, BuildOperationExecutor buildOperationExecutor, boolean logStackTraces) {
        return service == null
            ? NullBuildCacheServiceHandle.INSTANCE
            : toNonNullHandle(service, push, role, buildOperationExecutor, logStackTraces);
    }

    private static BuildCacheServiceHandle toNonNullHandle(BuildCacheService service, boolean push, BuildCacheServiceRole role, BuildOperationExecutor buildOperationExecutor, boolean logStackTraces) {
        if (role == BuildCacheServiceRole.LOCAL) {
            return new BaseBuildCacheServiceHandle(service, push, role, logStackTraces);
        } else {
            return new OpFiringBuildCacheServiceHandle(service, push, role, buildOperationExecutor, logStackTraces);
        }
    }

    private static LocalBuildCacheServiceHandle toHandle(LocalBuildCacheService local, boolean localPush) {
        if (local == null) {
            return NullLocalBuildCacheServiceHandle.INSTANCE;
        } else {
            return new DefaultLocalBuildCacheServiceHandle(local, localPush);
        }
    }

}
