package com.whosly.gateway.console.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleApiTokenFilterTest {

    @Test
    void blankTokenAllowsAll() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/api/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/api/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Unauthorized");
    }

    @Test
    void bearerAndHeaderAccepted() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token");

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
    void staticConsoleNotFiltered() throws Exception {
        ConsoleApiTokenFilter filter = new ConsoleApiTokenFilter("secret-token");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/console/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        // shouldNotFilter → chain proceeds without auth check when path not /console/api
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }
}
