// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.*;

import org.junit.Assert;
import org.junit.Test;

public class AcceptHashTest
{
    @Test
    public void testHash()
    {
        Assert.assertThat(AcceptHash.hashKey("dGhlIHNhbXBsZSBub25jZQ=="),is("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
    }
}
