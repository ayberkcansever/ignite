/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.igfs.mapreduce.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.ipc.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.IgniteSystemProperties.*;
import static org.apache.ignite.cache.CacheMemoryMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.igfs.IgfsMode.*;
import static org.apache.ignite.internal.IgniteNodeAttributes.*;

/**
 * Fully operational Ignite file system processor.
 */
public class IgfsProcessor extends IgfsProcessorAdapter {
    /** Null GGFS name. */
    private static final String NULL_NAME = UUID.randomUUID().toString();

    /** Converts context to GGFS. */
    private static final IgniteClosure<IgfsContext,IgniteFs> CTX_TO_IGFS = new C1<IgfsContext, IgniteFs>() {
        @Override public IgniteFs apply(IgfsContext ggfsCtx) {
            return ggfsCtx.igfs();
        }
    };

    /** */
    private final ConcurrentMap<String, IgfsContext> igfsCache =
        new ConcurrentHashMap8<>();

    /**
     * @param ctx Kernal context.
     */
    public IgfsProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (ctx.config().isDaemon())
            return;

        IgfsConfiguration[] cfgs = ctx.config().getIgfsConfiguration();

        assert cfgs != null && cfgs.length > 0;

        validateLocalGgfsConfigurations(cfgs);

        // Start GGFS instances.
        for (IgfsConfiguration cfg : cfgs) {
            IgfsContext ggfsCtx = new IgfsContext(
                ctx,
                new IgfsConfiguration(cfg),
                new IgfsMetaManager(),
                new IgfsDataManager(),
                new IgfsServerManager(),
                new IgfsFragmentizerManager());

            // Start managers first.
            for (IgfsManager mgr : ggfsCtx.managers())
                mgr.start(ggfsCtx);

            igfsCache.put(maskName(cfg.getName()), ggfsCtx);
        }

        if (log.isDebugEnabled())
            log.debug("GGFS processor started.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws IgniteCheckedException {
        if (ctx.config().isDaemon())
            return;

        if (!getBoolean(IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK)) {
            for (ClusterNode n : ctx.discovery().remoteNodes())
                checkGgfsOnRemoteNode(n);
        }

        for (IgfsContext ggfsCtx : igfsCache.values())
            for (IgfsManager mgr : ggfsCtx.managers())
                mgr.onKernalStart();
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) {
        // Stop GGFS instances.
        for (IgfsContext ggfsCtx : igfsCache.values()) {
            if (log.isDebugEnabled())
                log.debug("Stopping igfs: " + ggfsCtx.configuration().getName());

            List<IgfsManager> mgrs = ggfsCtx.managers();

            for (ListIterator<IgfsManager> it = mgrs.listIterator(mgrs.size()); it.hasPrevious();) {
                IgfsManager mgr = it.previous();

                mgr.stop(cancel);
            }

            ggfsCtx.igfs().stop();
        }

        igfsCache.clear();

        if (log.isDebugEnabled())
            log.debug("GGFS processor stopped.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        for (IgfsContext ggfsCtx : igfsCache.values()) {
            if (log.isDebugEnabled())
                log.debug("Stopping igfs: " + ggfsCtx.configuration().getName());

            List<IgfsManager> mgrs = ggfsCtx.managers();

            for (ListIterator<IgfsManager> it = mgrs.listIterator(mgrs.size()); it.hasPrevious();) {
                IgfsManager mgr = it.previous();

                mgr.onKernalStop(cancel);
            }
        }

        if (log.isDebugEnabled())
            log.debug("Finished executing GGFS processor onKernalStop() callback.");
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> GGFS processor memory stats [grid=" + ctx.gridName() + ']');
        X.println(">>>   ggfsCacheSize: " + igfsCache.size());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public Collection<IgniteFs> igfss() {
        return F.viewReadOnly(igfsCache.values(), CTX_TO_IGFS);
    }

    /** {@inheritDoc} */
    @Override @Nullable public IgniteFs igfs(@Nullable String name) {
        IgfsContext ggfsCtx = igfsCache.get(maskName(name));

        return ggfsCtx == null ? null : ggfsCtx.igfs();
    }

    /** {@inheritDoc} */
    @Override @Nullable public Collection<IpcServerEndpoint> endpoints(@Nullable String name) {
        IgfsContext igfsCtx = igfsCache.get(maskName(name));

        return igfsCtx == null ? Collections.<IpcServerEndpoint>emptyList() : igfsCtx.server().endpoints();
    }

    /** {@inheritDoc} */
    @Nullable @Override public ComputeJob createJob(IgfsJob job, @Nullable String ggfsName, IgfsPath path,
        long start, long len, IgfsRecordResolver recRslv) {
        return new IgfsJobImpl(job, ggfsName, path, start, len, recRslv);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void addAttributes(Map<String, Object> attrs) throws IgniteCheckedException {
        super.addAttributes(attrs);

        IgniteConfiguration gridCfg = ctx.config();

        // Node doesn't have GGFS if it:
        // is daemon;
        // doesn't have configured GGFS;
        // doesn't have configured caches.
        if (gridCfg.isDaemon() || F.isEmpty(gridCfg.getIgfsConfiguration()) ||
            F.isEmpty(gridCfg.getCacheConfiguration()))
            return;

        final Map<String, CacheConfiguration> cacheCfgs = new HashMap<>();

        F.forEach(gridCfg.getCacheConfiguration(), new CI1<CacheConfiguration>() {
            @Override public void apply(CacheConfiguration c) {
                cacheCfgs.put(c.getName(), c);
            }
        });

        Collection<IgfsAttributes> attrVals = new ArrayList<>();

        assert gridCfg.getIgfsConfiguration() != null;

        for (IgfsConfiguration ggfsCfg : gridCfg.getIgfsConfiguration()) {
            CacheConfiguration cacheCfg = cacheCfgs.get(ggfsCfg.getDataCacheName());

            if (cacheCfg == null)
                continue; // No cache for the given GGFS configuration.

            CacheAffinityKeyMapper affMapper = cacheCfg.getAffinityMapper();

            if (!(affMapper instanceof IgfsGroupDataBlocksKeyMapper))
                // Do not create GGFS attributes for such a node nor throw error about invalid configuration.
                // Configuration will be validated later, while starting GridGgfsProcessor.
                continue;

            attrVals.add(new IgfsAttributes(
                ggfsCfg.getName(),
                ggfsCfg.getBlockSize(),
                ((IgfsGroupDataBlocksKeyMapper)affMapper).groupSize(),
                ggfsCfg.getMetaCacheName(),
                ggfsCfg.getDataCacheName(),
                ggfsCfg.getDefaultMode(),
                ggfsCfg.getPathModes(),
                ggfsCfg.isFragmentizerEnabled()));
        }

        attrs.put(ATTR_IGFS, attrVals.toArray(new IgfsAttributes[attrVals.size()]));
    }

    /**
     * @param name Cache name.
     * @return Masked name accounting for {@code nulls}.
     */
    private String maskName(@Nullable String name) {
        return name == null ? NULL_NAME : name;
    }

    /**
     * Validates local GGFS configurations. Compares attributes only for GGFSes with same name.
     * @param cfgs GGFS configurations
     * @throws IgniteCheckedException If any of GGFS configurations is invalid.
     */
    private void validateLocalGgfsConfigurations(IgfsConfiguration[] cfgs) throws IgniteCheckedException {
        Collection<String> cfgNames = new HashSet<>();

        for (IgfsConfiguration cfg : cfgs) {
            String name = cfg.getName();

            if (cfgNames.contains(name))
                throw new IgniteCheckedException("Duplicate GGFS name found (check configuration and " +
                    "assign unique name to each): " + name);

            GridCacheAdapter<Object, Object> dataCache = ctx.cache().internalCache(cfg.getDataCacheName());

            if (dataCache == null)
                throw new IgniteCheckedException("Data cache is not configured locally for GGFS: " + cfg);

            if (dataCache.configuration().isQueryIndexEnabled())
                throw new IgniteCheckedException("GGFS data cache cannot start with enabled query indexing.");

            GridCache<Object, Object> metaCache = ctx.cache().cache(cfg.getMetaCacheName());

            if (metaCache == null)
                throw new IgniteCheckedException("Metadata cache is not configured locally for GGFS: " + cfg);

            if (metaCache.configuration().isQueryIndexEnabled())
                throw new IgniteCheckedException("GGFS metadata cache cannot start with enabled query indexing.");

            if (F.eq(cfg.getDataCacheName(), cfg.getMetaCacheName()))
                throw new IgniteCheckedException("Cannot use same cache as both data and meta cache: " + cfg.getName());

            if (!(dataCache.configuration().getAffinityMapper() instanceof IgfsGroupDataBlocksKeyMapper))
                throw new IgniteCheckedException("Invalid GGFS data cache configuration (key affinity mapper class should be " +
                    IgfsGroupDataBlocksKeyMapper.class.getSimpleName() + "): " + cfg);

            long maxSpaceSize = cfg.getMaxSpaceSize();

            if (maxSpaceSize > 0) {
                // Max space validation.
                long maxHeapSize = Runtime.getRuntime().maxMemory();
                long offHeapSize = dataCache.configuration().getOffHeapMaxMemory();

                if (offHeapSize < 0 && maxSpaceSize > maxHeapSize)
                    // Offheap is disabled.
                    throw new IgniteCheckedException("Maximum GGFS space size cannot be greater that size of available heap " +
                        "memory [maxHeapSize=" + maxHeapSize + ", maxGgfsSpaceSize=" + maxSpaceSize + ']');
                else if (offHeapSize > 0 && maxSpaceSize > maxHeapSize + offHeapSize)
                    // Offheap is enabled, but limited.
                    throw new IgniteCheckedException("Maximum GGFS space size cannot be greater than size of available heap " +
                        "memory and offheap storage [maxHeapSize=" + maxHeapSize + ", offHeapSize=" + offHeapSize +
                        ", maxGgfsSpaceSize=" + maxSpaceSize + ']');
            }

            if (dataCache.configuration().getCacheMode() == PARTITIONED) {
                int backups = dataCache.configuration().getBackups();

                if (backups != 0)
                    throw new IgniteCheckedException("GGFS data cache cannot be used with backups (set backup count " +
                        "to 0 and restart the grid): " + cfg.getDataCacheName());
            }

            if (cfg.getMaxSpaceSize() == 0 && dataCache.configuration().getMemoryMode() == OFFHEAP_VALUES)
                U.warn(log, "GGFS max space size is not specified but data cache values are stored off-heap (max " +
                    "space will be limited to 80% of max JVM heap size): " + cfg.getName());

            boolean secondary = cfg.getDefaultMode() == PROXY;

            if (cfg.getPathModes() != null) {
                for (Map.Entry<String, IgfsMode> mode : cfg.getPathModes().entrySet()) {
                    if (mode.getValue() == PROXY)
                        secondary = true;
                }
            }

            if (secondary) {
                // When working in any mode except of primary, secondary FS config must be provided.
                assertParameter(cfg.getSecondaryFileSystem() != null,
                    "secondaryFileSystem cannot be null when mode is SECONDARY");
            }

            cfgNames.add(name);
        }
    }

    /**
     * Check GGFS config on remote node.
     *
     * @param rmtNode Remote node.
     * @throws IgniteCheckedException If check failed.
     */
    private void checkGgfsOnRemoteNode(ClusterNode rmtNode) throws IgniteCheckedException {
        IgfsAttributes[] locAttrs = ctx.discovery().localNode().attribute(IgniteNodeAttributes.ATTR_IGFS);
        IgfsAttributes[] rmtAttrs = rmtNode.attribute(IgniteNodeAttributes.ATTR_IGFS);

        if (F.isEmpty(locAttrs) || F.isEmpty(rmtAttrs))
            return;

        assert rmtAttrs != null && locAttrs != null;

        for (IgfsAttributes rmtAttr : rmtAttrs)
            for (IgfsAttributes locAttr : locAttrs) {
                // Checking the use of different caches on the different GGFSes.
                if (!F.eq(rmtAttr.igfsName(), locAttr.igfsName())) {
                    if (F.eq(rmtAttr.metaCacheName(), locAttr.metaCacheName()))
                        throw new IgniteCheckedException("Meta cache names should be different for different GGFS instances " +
                            "configuration (fix configuration or set " +
                            "-D" + IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK + "=true system " +
                            "property) [metaCacheName=" + rmtAttr.metaCacheName() +
                            ", locNodeId=" + ctx.localNodeId() +
                            ", rmtNodeId=" + rmtNode.id() +
                            ", locGgfsName=" + locAttr.igfsName() +
                            ", rmtGgfsName=" + rmtAttr.igfsName() + ']');

                    if (F.eq(rmtAttr.dataCacheName(), locAttr.dataCacheName()))
                        throw new IgniteCheckedException("Data cache names should be different for different GGFS instances " +
                            "configuration (fix configuration or set " +
                            "-D" + IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK + "=true system " +
                            "property)[dataCacheName=" + rmtAttr.dataCacheName() +
                            ", locNodeId=" + ctx.localNodeId() +
                            ", rmtNodeId=" + rmtNode.id() +
                            ", locGgfsName=" + locAttr.igfsName() +
                            ", rmtGgfsName=" + rmtAttr.igfsName() + ']');

                    continue;
                }

                // Compare other attributes only for GGFSes with same name.
                checkSame("Data block size", "BlockSize", rmtNode.id(), rmtAttr.blockSize(),
                    locAttr.blockSize(), rmtAttr.igfsName());

                checkSame("Affinity mapper group size", "GrpSize", rmtNode.id(), rmtAttr.groupSize(),
                    locAttr.groupSize(), rmtAttr.igfsName());

                checkSame("Meta cache name", "MetaCacheName", rmtNode.id(), rmtAttr.metaCacheName(),
                    locAttr.metaCacheName(), rmtAttr.igfsName());

                checkSame("Data cache name", "DataCacheName", rmtNode.id(), rmtAttr.dataCacheName(),
                    locAttr.dataCacheName(), rmtAttr.igfsName());

                checkSame("Default mode", "DefaultMode", rmtNode.id(), rmtAttr.defaultMode(),
                    locAttr.defaultMode(), rmtAttr.igfsName());

                checkSame("Path modes", "PathModes", rmtNode.id(), rmtAttr.pathModes(),
                    locAttr.pathModes(), rmtAttr.igfsName());

                checkSame("Fragmentizer enabled", "FragmentizerEnabled", rmtNode.id(), rmtAttr.fragmentizerEnabled(),
                    locAttr.fragmentizerEnabled(), rmtAttr.igfsName());
            }
    }

    private void checkSame(String name, String propName, UUID rmtNodeId, Object rmtVal, Object locVal, String igfsName)
        throws IgniteCheckedException {
        if (!F.eq(rmtVal, locVal))
            throw new IgniteCheckedException(name + " should be the same on all nodes in grid for GGFS configuration " +
                "(fix configuration or set " +
                "-D" + IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK + "=true system " +
                "property ) [rmtNodeId=" + rmtNodeId +
                ", rmt" + propName + "=" + rmtVal +
                ", loc" + propName + "=" + locVal +
                ", ggfName=" + igfsName + ']');
    }
}
