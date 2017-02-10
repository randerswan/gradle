/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.process.internal.daemon;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.ClasspathUtil;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.internal.operations.BuildOperationWorkerRegistry;
import org.gradle.internal.operations.BuildOperationWorkerRegistry.Operation;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.daemon.WorkerDaemonConfiguration;
import org.gradle.process.daemon.WorkerDaemonExecutionException;
import org.gradle.process.daemon.WorkerDaemonExecutor;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DefaultWorkerDaemonExecutor implements WorkerDaemonExecutor {
    private final ListeningExecutorService executor;
    private final WorkerDaemonFactory workerDaemonFactory;
    private final Class<? extends WorkerDaemonProtocol> serverImplementationClass;
    private final FileResolver fileResolver;
    private final BuildOperationWorkerRegistry buildOperationWorkerRegistry;

    public DefaultWorkerDaemonExecutor(WorkerDaemonFactory workerDaemonFactory, FileResolver fileResolver, Class<? extends WorkerDaemonProtocol> serverImplementationClass, ExecutorFactory executorFactory, BuildOperationWorkerRegistry buildOperationWorkerRegistry) {
        this.workerDaemonFactory = workerDaemonFactory;
        this.fileResolver = fileResolver;
        this.serverImplementationClass = serverImplementationClass;
        this.executor = MoreExecutors.listeningDecorator(executorFactory.create("Worker Daemon Execution"));
        this.buildOperationWorkerRegistry = buildOperationWorkerRegistry;
    }

    @Override
    public ListenableFuture<?> submit(Class<? extends Runnable> actionClass, Action<WorkerDaemonConfiguration> configAction) {
        WorkerDaemonConfiguration configuration = new DefaultWorkerDaemonConfiguration(fileResolver);
        configAction.execute(configuration);
        WorkSpec spec = new ParamSpec(configuration.getParams());
        WorkerDaemonAction action = new WorkerDaemonRunnableAction(actionClass);
        return submit(action, spec, configuration.getForkOptions().getWorkingDir(), getDaemonForkOptions(actionClass, configuration));
    }

    private ListenableFuture<?> submit(final WorkerDaemonAction action, final WorkSpec spec, final File workingDir, final DaemonForkOptions daemonForkOptions) {
        final Operation currentOperation = buildOperationWorkerRegistry.getCurrent();
        ListenableFuture<WorkerDaemonResult> workerDaemonResult = executor.submit(new Callable<WorkerDaemonResult>() {
            @Override
            public WorkerDaemonResult call() throws Exception {
                try {
                    WorkerDaemon daemon = workerDaemonFactory.getDaemon(serverImplementationClass, workingDir, daemonForkOptions);
                    return daemon.execute(action, spec, currentOperation);
                } catch (Throwable t) {
                    throw new WorkerExecutionException(action.getDescription(), t);
                }
            }
        });
        return new RunnableWorkFuture(action.getDescription(), workerDaemonResult);
    }

    public void await(Collection<ListenableFuture<?>> futures) throws WorkerDaemonExecutionException {
        final List<Throwable> failures = Lists.newArrayList();
        final CountDownLatch completion = new CountDownLatch(futures.size());
        for (ListenableFuture<?> result : futures) {
            Futures.addCallback(result, new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object result) {
                    completion.countDown();
                }

                @Override
                public void onFailure(Throwable t) {
                    failures.add(t);
                    completion.countDown();
                }
            });
        }

        try {
            completion.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }

        if (failures.size() > 0) {
            throw workerDaemonExecutionException(failures);
        }
    }

    private WorkerDaemonExecutionException workerDaemonExecutionException(List<Throwable> failures) {
        if (failures.size() == 1) {
            throw new WorkerDaemonExecutionException("There was a failure while executing work items", failures);
        } else {
            throw new WorkerDaemonExecutionException("There were multiple failures while executing work items", failures);
        }
    }

    DaemonForkOptions getDaemonForkOptions(Class<?> actionClass, WorkerDaemonConfiguration configuration) {
        Iterable<Class<?>> paramTypes = CollectionUtils.collect(configuration.getParams(), new Transformer<Class<?>, Object>() {
            @Override
            public Class<?> transform(Object o) {
                return o.getClass();
            }
        });
        return toDaemonOptions(actionClass, paramTypes, configuration.getForkOptions(), configuration.getClasspath());
    }

    private DaemonForkOptions toDaemonOptions(Class<?> actionClass, Iterable<Class<?>> paramClasses, JavaForkOptions forkOptions, Iterable<File> classpath) {
        ImmutableSet.Builder<File> classpathBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<String> sharedPackagesBuilder = ImmutableSet.builder();

        if (classpath != null) {
            classpathBuilder.addAll(classpath);
        }

        addVisibilityFor(actionClass, classpathBuilder, sharedPackagesBuilder);

        for (Class<?> paramClass : paramClasses) {
            addVisibilityFor(paramClass, classpathBuilder, sharedPackagesBuilder);
        }

        Iterable<File> daemonClasspath = classpathBuilder.build();
        Iterable<String> daemonSharedPackages = sharedPackagesBuilder.build();

        return new DaemonForkOptions(forkOptions.getMinHeapSize(), forkOptions.getMaxHeapSize(), forkOptions.getAllJvmArgs(), daemonClasspath, daemonSharedPackages);
    }

    private static void addVisibilityFor(Class<?> visibleClass, ImmutableSet.Builder<File> classpathBuilder, ImmutableSet.Builder<String> sharedPackagesBuilder) {
        if (visibleClass.getClassLoader() != null) {
            classpathBuilder.addAll(ClasspathUtil.getClasspath(visibleClass.getClassLoader()).getAsFiles());
        }

        if (visibleClass.getPackage() == null || "".equals(visibleClass.getPackage().getName())) {
            sharedPackagesBuilder.add(FilteringClassLoader.DEFAULT_PACKAGE);
        } else {
            sharedPackagesBuilder.add(visibleClass.getPackage().getName());
        }
    }

    private static class RunnableWorkFuture implements ListenableFuture<Void> {
        private final String description;
        private final ListenableFuture<WorkerDaemonResult> delegate;

        public RunnableWorkFuture(String description, ListenableFuture<WorkerDaemonResult> delegate) {
            this.description = description;
            this.delegate = delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            WorkerDaemonResult result = delegate.get();
            throwIfNotSuccess(result);
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            WorkerDaemonResult result = delegate.get(timeout, unit);
            throwIfNotSuccess(result);
            return null;
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            delegate.addListener(listener, executor);
        }

        private void throwIfNotSuccess(WorkerDaemonResult result) throws ExecutionException {
            if (!result.isSuccess()) {
                throw new WorkerExecutionException(description, result.getException());
            }
        }
    }

    @Contextual
    private static class WorkerExecutionException extends ExecutionException {
        public WorkerExecutionException(String description) {
            this(description, null);
        }

        public WorkerExecutionException(String description, Throwable cause) {
            super("A failure occurred while executing " + description, cause);
        }
    }
}
