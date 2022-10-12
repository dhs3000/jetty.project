//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.util.resource.Resource;

/**
 * HttpContent created from a {@link Resource}.
 * <p>The HttpContent is used to server static content that is not
 * cached. So fields and values are only generated as need be an not
 * kept for reuse</p>
 */
public class ResourceHttpContent implements HttpContent
{
    final Resource _resource;
    final Path _path;
    final String _contentType;
    final HttpField _etag;
    Map<CompressedContentFormat, HttpContent> _precompressedContents;

    public ResourceHttpContent(final Resource resource, final String contentType)
    {
        this(resource, contentType, null);
    }

    public ResourceHttpContent(final Resource resource, final String contentType, Map<CompressedContentFormat, HttpContent> precompressedContents)
    {
        _resource = resource;
        _path = resource.getPath();
        _contentType = contentType;
        _etag = EtagUtils.createWeakEtagField(resource);
        if (precompressedContents == null)
        {
            _precompressedContents = null;
        }
        else
        {
            _precompressedContents = new HashMap<>(precompressedContents.size());
            for (Map.Entry<CompressedContentFormat, HttpContent> entry : precompressedContents.entrySet())
            {
                _precompressedContents.put(entry.getKey(), new PrecompressedHttpContent(this, entry.getValue(), entry.getKey()));
            }
        }
    }

    @Override
    public String getContentTypeValue()
    {
        return _contentType;
    }

    @Override
    public HttpField getContentType()
    {
        return _contentType == null ? null : new HttpField(HttpHeader.CONTENT_TYPE, _contentType);
    }

    @Override
    public HttpField getContentEncoding()
    {
        return null;
    }

    @Override
    public String getContentEncodingValue()
    {
        return null;
    }

    @Override
    public String getCharacterEncoding()
    {
        return _contentType == null ? null : MimeTypes.getCharsetFromContentType(_contentType);
    }

    @Override
    public Type getMimeType()
    {
        return _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(_contentType));
    }

    @Override
    public HttpField getLastModified()
    {
        Instant lm = _resource.lastModified();
        return new HttpField(HttpHeader.LAST_MODIFIED, DateGenerator.formatDate(lm));
    }

    @Override
    public String getLastModifiedValue()
    {
        Instant lm = _resource.lastModified();
        return DateGenerator.formatDate(lm);
    }

    @Override
    public HttpField getETag()
    {
        return _etag;
    }

    @Override
    public String getETagValue()
    {
        if (_etag == null)
            return null;
        return _etag.getValue();
    }

    @Override
    public HttpField getContentLength()
    {
        long l = getContentLengthValue();
        return l == -1 ? null : new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, l);
    }

    @Override
    public long getContentLengthValue()
    {
        return _resource.length();
    }

    @Override
    public Resource getResource()
    {
        return _resource;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s,ct=%s,c=%b}", this.getClass().getSimpleName(), hashCode(), _resource, _contentType, _precompressedContents != null);
    }

    @Override
    public Map<CompressedContentFormat, HttpContent> getPrecompressedContents()
    {
        return _precompressedContents;
    }

    @Override
    public ByteBuffer getBuffer()
    {
        return null;
    }

    @Override
    public void release()
    {
    }
}