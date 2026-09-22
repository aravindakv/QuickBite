package com.quickbite.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import java.io.IOException;
    
public class CorrelationFilter extends OncePerRequestFilter
 {
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String sid = req.getHeader(Headers.SESSION_ID);
        String cid = req.getHeader(Headers.CORRELATION_ID);
        if(cid == null || cid.isEmpty()) {
            cid = java.util.UUID.randomUUID().toString();
        }
        MDC.put(Headers.SESSION_ID, sid == null ? "-" : sid);
        MDC.put(Headers.CORRELATION_ID, cid);
        res.setHeader(Headers.CORRELATION_ID, cid);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(Headers.SESSION_ID);
            MDC.remove(Headers.CORRELATION_ID);
        }
    }
}
