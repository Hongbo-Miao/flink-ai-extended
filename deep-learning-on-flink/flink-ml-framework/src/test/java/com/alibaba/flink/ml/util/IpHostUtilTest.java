/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.flink.ml.util;

import org.apache.http.conn.util.InetAddressUtils;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertTrue;

public class IpHostUtilTest {
	@Test
	public void getLocalIp() throws Exception {
		assertTrue(IpHostUtil.getIpAddress().getClass() == String.class);
	}

	@Test
	public void getFreePort() throws Exception {
		assertTrue(IpHostUtil.getFreePort() > 0);
	}

	@Test
	public void testGetLanIp() throws Exception {
		final String ip = IpHostUtil.getLocalHostLANAddress().getHostAddress();
		assertTrue(InetAddressUtils.isIPv4Address(ip) || InetAddressUtils.isIPv6Address(ip));
	}
}