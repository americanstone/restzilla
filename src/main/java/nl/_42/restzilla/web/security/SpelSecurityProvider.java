/*
 * (C) 2014 42 bv (www.42.nl). All rights reserved.
 */
package nl._42.restzilla.web.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler;

import javax.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * Implementation that evaluates SPEL expressions.
 *
 * @author Jeroen van Schagen
 * @since Sep 8, 2015
 */
public class SpelSecurityProvider implements SecurityProvider {
    
    /**
     * Web security expression handler.
     */
    private DefaultWebSecurityExpressionHandler handler = new DefaultWebSecurityExpressionHandler();

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAuthorized(String[] expressions, HttpServletRequest request) {
        if (expressions.length > 0) {
            Authentication authentication = getAuthentication(request);
            FilterInvocation invocation = new FilterInvocation(request.getServletPath(), request.getMethod());
            EvaluationContext context = handler.createEvaluationContext(authentication, invocation);
            for (String expression : expressions) {
                if (StringUtils.isNotBlank(expression)) {
                    ExpressionParser parser = handler.getExpressionParser();
                    if (!ExpressionUtils.evaluateAsBoolean(parser.parseExpression(expression), context)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private Authentication getAuthentication(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal instanceof Authentication) {
            return (Authentication) principal;
        } else {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                authentication = anonymous();
            }
            return authentication;
        }
    }

    private AnonymousAuthenticationToken anonymous() {
        return new AnonymousAuthenticationToken("anonymousUser", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }
    
    /**
     * Configure the default web security expression handler.
     * @param handler the handler to set
     */
    @Autowired(required = false)
    public void setHandler(DefaultWebSecurityExpressionHandler handler) {
        this.handler = handler;
    }

}
