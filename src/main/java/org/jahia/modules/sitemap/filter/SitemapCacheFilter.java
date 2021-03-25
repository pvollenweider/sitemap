/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2021 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms &amp; Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.sitemap.filter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.modules.sitemap.utils.ConfigServiceUtils;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

/**
 * Filter that creates sitemap file nodes for caching.
 *
 * Since it can be resource-intensive to go through and generate all sitemap entries,
 * and since the built-in Jahia view caching isn't guaranteed (i.e. depending on traffic, less-used caches can get invalidated at any time)
 * we've added a custom file-based caching layer to the sitemap.xml views that will be invalidated only after expiration has passed
 * (currently set at 4 hours).
 */
@Component(service = RenderFilter.class)
public class SitemapCacheFilter extends AbstractFilter {

    private static final Logger logger = LoggerFactory.getLogger(SitemapCacheFilter.class);

    private static final long EXPIRATION = ConfigServiceUtils.getCacheDuration();

    /** Node name prefix for sitemap file caches */
    private static final String CACHE_NAME = "sitemap-cache";

    /** Session attribute flag to check in the sitemap views whether view needs to re-render or not */
    private static final String RENDER_FLAG_ATTR = "refreshSitemapSession";


    @Activate
    public void activate() {
        setPriority(15f);
        setApplyOnNodeTypes("jseont:sitemapResource,jseont:sitemap");
        setApplyOnModes("live");
        setDescription("Filter for creating sitemap file nodes for caching");
        logger.debug("Activated FileCacheFilter");
    }

    /**
     * Check if view needs to be re-rendered or not and add a session flag that the view can then check to determine that logic.
     * Also flush cache here if view needs to be re-rendered.
     */
    @Override public String prepare(RenderContext renderContext, Resource resource, RenderChain chain) throws Exception {
        if (!needsCaching(resource)) return null;

        JCRNodeWrapper cacheNode = getCacheNode(resource.getNode());
        boolean refreshSitemap = (cacheNode == null) || isExpired(cacheNode);
        renderContext.getRequest().getSession().setAttribute(RENDER_FLAG_ATTR, refreshSitemap);
        if (refreshSitemap) { // manually flush jahia cache prior to render
            CacheHelper.flushOutputCachesForPath(resource.getPath(), false);
        }
        return null;
    }

    @Override
    public String execute(String previousOut, RenderContext renderContext, Resource resource, RenderChain chain)
            throws RepositoryException
    {
        if (!needsCaching(resource)) return previousOut;

        JCRNodeWrapper sitemapNode = resource.getNode();
        boolean refreshSitemap = (Boolean) renderContext.getRequest().getSession().getAttribute(RENDER_FLAG_ATTR);
        InputStream inputStream = null;
        try {
            if (refreshSitemap && StringUtils.isNotBlank(previousOut)) {
                inputStream = IOUtils.toInputStream(previousOut, StandardCharsets.UTF_8);
                refreshSitemapCache(sitemapNode, inputStream);
                return previousOut;
            } else {
                JCRNodeWrapper cacheNode = getCacheNode(sitemapNode);
                inputStream = cacheNode.getFileContent().downloadFile();
                String out = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                return out;
            }
        } catch (IOException e) {
            logger.error("Unable to read sitemap file cache contents.");
            renderContext.getResponse().setStatus(HttpServletResponse.SC_NOT_FOUND);
            return null;
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * Create or refresh file cache for given parent for a given locale
     * Uses parent session to save
     * https://stackoverflow.com/questions/4685959/get-file-out-of-jcr-file-node
     */
    private JCRNodeWrapper refreshSitemapCache(JCRNodeWrapper sitemapNode, InputStream data) throws RepositoryException {
        String cacheName = getCacheName(sitemapNode);
        String nodePath = sitemapNode.getPath();

        // use system session to be able to save file cache in live workspace
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null,
                Constants.LIVE_WORKSPACE, null, new JCRCallback<JCRNodeWrapper>() {
            @Override public JCRNodeWrapper doInJCR(JCRSessionWrapper session) throws RepositoryException {
                JCRNodeWrapper node = session.getNode(nodePath);
                node.uploadFile(cacheName, data, "application/xml");
                session.save();
                return node;
            }
        });
    }


    /** Apply file caching only for default template */
    public boolean needsCaching(Resource resource) {
        return "default".equalsIgnoreCase(resource.getTemplate());
    }

    public JCRNodeWrapper getCacheNode(JCRNodeWrapper node) throws RepositoryException {
        if (node == null) return null;
        String cacheName = getCacheName(node);
        return (node.hasNode(cacheName)) ? node.getNode(cacheName) : null;
    }

    public String getCacheName(JCRNodeWrapper node) {
        return CACHE_NAME + "-" + node.getLanguage();
    }

    private boolean isExpired(JCRNodeWrapper cacheNode) {
        long exp = EXPIRATION;
        Date lastModified = cacheNode.getContentLastModifiedAsDate();
        long expirationInMs = lastModified.getTime() + exp;
        return System.currentTimeMillis() > expirationInMs;
    }

}
