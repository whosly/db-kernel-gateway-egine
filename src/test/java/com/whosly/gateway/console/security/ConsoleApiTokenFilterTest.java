package com.whosly.gateway.console.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleApiTokenFilterTest {

    @Test
    void blankTokenAllowsAll() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("", "");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/api/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token", "");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/api/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Unauthorized");
    }

    @Test
    void bearerAndHeaderAccepted() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token", "");

        MockHttpServletRequest bearer = new MockHttpServletRequest("GET", "/console/api/health");
        bearer.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(bearer, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(200);

        MockHttpServletRequest hdr = new MockHttpServletRequest("GET", "/console/api/health");
        hdr.addHeader("X-Console-Token", "secret-token");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(hdr, res2, new MockFilterChain());
        assertThat(res2.getStatus()).isEqualTo(200);
    }

    @Test
    void readTokenAllowsGetButNotWrite() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("write-token", "read-only");

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/console/api/overview");
        get.addHeader("X-Console-Token", "read-only");
        MockHttpServletResponse getRes = new MockHttpServletResponse();
        filter.doFilter(get, getRes, new MockFilterChain());
        assertThat(getRes.getStatus()).isEqualTo(200);

        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/console/api/risk-policy");
        put.addHeader("X-Console-Token", "read-only");
        MockHttpServletResponse putRes = new MockHttpServletResponse();
        filter.doFilter(put, putRes, new MockFilterChain());
        assertThat(putRes.getStatus()).isEqualTo(401);

        MockHttpServletRequest putOk = new MockHttpServletRequest("PUT", "/console/api/risk-policy");
        putOk.addHeader("X-Console-Token", "write-token");
        MockHttpServletResponse putOkRes = new MockHttpServletResponse();
        filter.doFilter(putOk, putOkRes, new MockFilterChain());
        assertThat(putOkRes.getStatus()).isEqualTo(200);
    }

    @Test
    void staticConsoleNotFiltered() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token", "");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }
}
