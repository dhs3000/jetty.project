//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;

class CrossContextDispatcher implements RequestDispatcher
{
    public static final String ORIGINAL_URI = "org.eclipse.jetty.dispatch.originalURI";
    public static final String ORIGINAL_SERVLET_PATH = "org.eclipse.jetty.dispatch.originalServletPath";
    public static final String ORIGINAL_PATH_INFO = "org.eclipse.jetty.dispatch.originalPathInfo";
    public static final String ORIGINAL_QUERY_STRING = "org.eclipse.jetty.dispatch.originalQueryString";
    public static final String ORIGINAL_SERVLET_MAPPING = "org.eclipse.jetty.dispatch.originalServletMapping";
    public static final String ORIGINAL_SERVLET_CONTEXT = "org.eclipse.jetty.dispatch.originalServletContext";
    public static final String ORIGINAL_CONTEXT_PATH = "org.ecipse.jetty.dispatch.originalContextPath";

    public static final Set<String> ATTRIBUTES = Set.of(
        RequestDispatcher.FORWARD_REQUEST_URI,
        RequestDispatcher.FORWARD_MAPPING,
        RequestDispatcher.FORWARD_CONTEXT_PATH,
        RequestDispatcher.FORWARD_SERVLET_PATH,
        RequestDispatcher.FORWARD_QUERY_STRING,
        RequestDispatcher.FORWARD_PATH_INFO,
        RequestDispatcher.INCLUDE_REQUEST_URI,
        RequestDispatcher.INCLUDE_MAPPING,
        RequestDispatcher.INCLUDE_CONTEXT_PATH,
        RequestDispatcher.INCLUDE_SERVLET_PATH,
        RequestDispatcher.INCLUDE_QUERY_STRING,
        RequestDispatcher.INCLUDE_PATH_INFO,
        // TODO MULTIPART_CONFIG_ELEMENT,
        org.eclipse.jetty.server.handler.ContextHandler.CROSS_CONTEXT_ATTRIBUTE,
        ORIGINAL_URI,
        ORIGINAL_QUERY_STRING,
        ORIGINAL_SERVLET_MAPPING,
        ORIGINAL_PATH_INFO,
        ORIGINAL_SERVLET_PATH,
        ORIGINAL_SERVLET_CONTEXT,
        ORIGINAL_CONTEXT_PATH,
        FormFields.class.getName()
    );

    private final CrossContextServletContext _targetContext;
    private final HttpURI _uri;
    private final String _decodedPathInContext;

    private class IncludeRequest extends ServletCoreRequest
    {
        public IncludeRequest(ContextHandler.CoreContextRequest coreContextRequest, HttpServletRequest httpServletRequest)
        {
            super(coreContextRequest, httpServletRequest, new Attributes.Synthetic(new ServletAttributes(httpServletRequest))
            {
                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    if (name == null)
                        return null;

                    //Servlet Spec 9.3.1 no include attributes if a named dispatcher
/*                    if (_namedServlet != null && name.startsWith(Dispatcher.__INCLUDE_PREFIX))
                        return null;*/

                    //Special include attributes refer to the target context and path
                    return switch (name)
                    {
                        case RequestDispatcher.INCLUDE_MAPPING -> null;
                        case RequestDispatcher.INCLUDE_SERVLET_PATH -> null;
                        case RequestDispatcher.INCLUDE_PATH_INFO ->  _decodedPathInContext;
                        case RequestDispatcher.INCLUDE_REQUEST_URI -> (_uri == null) ? null : _uri.getPath();
                        case RequestDispatcher.INCLUDE_CONTEXT_PATH -> _targetContext.getContextPath();
                        case RequestDispatcher.INCLUDE_QUERY_STRING -> (_uri == null) ? null : _uri.getQuery();
                        case org.eclipse.jetty.server.handler.ContextHandler.CROSS_CONTEXT_ATTRIBUTE -> DispatcherType.INCLUDE.toString();
                        default -> httpServletRequest.getAttribute(name);
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            });
            
            setAttribute(ORIGINAL_URI, getServletRequest().getRequestURI());
            setAttribute(ORIGINAL_SERVLET_PATH, getServletRequest().getServletPath());
            setAttribute(ORIGINAL_PATH_INFO, getServletRequest().getPathInfo());
            setAttribute(ORIGINAL_QUERY_STRING, httpServletRequest.getQueryString());
            setAttribute(ORIGINAL_SERVLET_MAPPING, getServletRequest().getHttpServletMapping());
            setAttribute(ORIGINAL_SERVLET_CONTEXT, getServletRequest().getServletContext());
            setAttribute(ORIGINAL_CONTEXT_PATH, getServletRequest().getContextPath());
        }

        @Override
        public HttpURI getHttpURI()
        {
            //return the uri of the dispatch target
            return _uri;
        }
    }

    private class IncludeResponse extends ServletCoreResponse
    {
        public IncludeResponse(ServletCoreRequest servletCoreRequest, HttpServletResponse httpServletResponse, Response baseResponse, org.eclipse.jetty.server.Response coreResponse)
        {
            super(servletCoreRequest, httpServletResponse, baseResponse, coreResponse, true);
        }
    }

    private class ForwardRequest extends ServletCoreRequest
    {
        /**
         * @param httpServletRequest the request to wrap
         */
        public ForwardRequest(ContextHandler.CoreContextRequest coreContextRequest, HttpServletRequest httpServletRequest)
        {
            super(coreContextRequest, httpServletRequest, new Attributes.Synthetic(new ServletAttributes(httpServletRequest))
            {
                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    return switch (name)
                    {
                        case RequestDispatcher.FORWARD_REQUEST_URI -> httpServletRequest.getRequestURI();
                        case RequestDispatcher.FORWARD_SERVLET_PATH -> httpServletRequest.getServletPath();
                        case RequestDispatcher.FORWARD_PATH_INFO -> httpServletRequest.getPathInfo();
                        case RequestDispatcher.FORWARD_CONTEXT_PATH -> httpServletRequest.getContextPath();
                        case RequestDispatcher.FORWARD_MAPPING -> httpServletRequest.getHttpServletMapping();
                        case RequestDispatcher.FORWARD_QUERY_STRING -> httpServletRequest.getQueryString();
                        case RequestDispatcher.INCLUDE_MAPPING -> REMOVED;
                        case RequestDispatcher.INCLUDE_REQUEST_URI -> REMOVED;
                        case RequestDispatcher.INCLUDE_CONTEXT_PATH -> REMOVED;
                        case RequestDispatcher.INCLUDE_QUERY_STRING -> REMOVED;
                        case RequestDispatcher.INCLUDE_SERVLET_PATH -> REMOVED;
                        case RequestDispatcher.INCLUDE_PATH_INFO -> REMOVED;
                        // TODO case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> httpServletRequest.getAttribute(ServletMultiPartFormData.class.getName());
                        case org.eclipse.jetty.server.handler.ContextHandler.CROSS_CONTEXT_ATTRIBUTE -> DispatcherType.FORWARD.toString();
                        default ->
                        {
                            if (FormFields.class.getName().equals(name))
                            {
                                Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(httpServletRequest));
                                yield baseRequest.peekParameters();
                            }
                            yield null;
                        }
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            });
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }
    }

    CrossContextDispatcher(CrossContextServletContext targetContext, HttpURI uri, String decodedPathInContext)
    {
        _targetContext = targetContext;
        _uri = uri;
        _decodedPathInContext = decodedPathInContext;
    }

    @Override
    public void forward(ServletRequest servletRequest, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpServletRequest = (servletRequest instanceof HttpServletRequest) ? ((HttpServletRequest)servletRequest) : new ServletRequestHttpWrapper(servletRequest);
        HttpServletResponse httpServletResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(servletRequest));
        Response baseResponse = baseRequest.getResponse();

        ContextHandler.CoreContextRequest coreContextRequest = baseRequest.getCoreRequest();
        org.eclipse.jetty.server.Response coreResponse = coreContextRequest.getHttpChannel().getCoreResponse();
        baseResponse.resetForForward();

        ForwardRequest forwardRequest = new ForwardRequest(coreContextRequest, httpServletRequest);
        ServletCoreResponse servletCoreResponse = new ServletCoreResponse(forwardRequest, httpServletResponse, baseResponse, coreResponse, false);

        try (Blocker.Callback callback = Blocker.callback())
        {
            _targetContext.getTargetContext().getContextHandler().handle(forwardRequest, servletCoreResponse, callback);
            callback.block();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }

        // If we are not async and not closed already, then close via the possibly wrapped response.
        if (!baseRequest.isAsyncStarted() && !servletCoreResponse.hasLastWrite())
        {
            Closeable closeable;
            try
            {
                closeable = response.getOutputStream();
            }
            catch (IllegalStateException e)
            {
                closeable = response.getWriter();
            }
            IO.close(closeable);
        }
    }

    @Override
    public void include(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException
    {
        HttpServletRequest httpServletRequest = (servletRequest instanceof HttpServletRequest) ? ((HttpServletRequest)servletRequest) : new ServletRequestHttpWrapper(servletRequest);
        HttpServletResponse httpServletResponse = (servletResponse instanceof HttpServletResponse) ? (HttpServletResponse)servletResponse : new ServletResponseHttpWrapper(servletResponse);
        // TODO ServletContextResponse servletContextResponse = ServletContextResponse.getServletContextResponse(servletResponse);
        /*
        IncludeRequest includeRequest = new IncludeRequest(httpServletRequest);
        IncludeResponse includeResponse = new IncludeResponse(includeRequest, httpServletResponse);

        try (Blocker.Callback callback = Blocker.callback())
        {
            _targetContext.getTargetContext().getContextHandler().handle(includeRequest, includeResponse, callback);
            callback.block();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }

         */
    }
}
