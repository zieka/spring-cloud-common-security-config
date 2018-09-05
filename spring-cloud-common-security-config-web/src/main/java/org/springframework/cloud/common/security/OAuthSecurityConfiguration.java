/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.common.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.Filter;

import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.AuthoritiesExtractor;
import org.springframework.boot.autoconfigure.security.oauth2.resource.ResourceServerProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.UserInfoTokenServices;
import org.springframework.cloud.common.security.support.DefaultAuthoritiesExtractor;
import org.springframework.cloud.common.security.support.ExternalOauth2ResourceAuthoritiesExtractor;
import org.springframework.cloud.common.security.support.OnSecurityEnabledAndOAuth2Enabled;
import org.springframework.cloud.common.security.support.SecurityConfigUtils;
import org.springframework.cloud.common.security.support.SecurityStateBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.filter.OAuth2AuthenticationFailureEvent;
import org.springframework.security.oauth2.client.filter.OAuth2ClientAuthenticationProcessingFilter;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeResourceDetails;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableOAuth2Client;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationManager;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationProcessingFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Setup Spring Security OAuth for the Rest Endpoints of Spring Cloud Data Flow.
 *
 * @author Gunnar Hillert
 * @author Ilayaperumal Gopinathan
 */
@EnableOAuth2Client
@Configuration
@Conditional(OnSecurityEnabledAndOAuth2Enabled.class)
public class OAuthSecurityConfiguration extends WebSecurityConfigurerAdapter {

	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(OAuthSecurityConfiguration.class);

	@Autowired
	protected SecurityStateBean securityStateBean;

	@Autowired
	protected SecurityProperties securityProperties;

	@Autowired
	protected OAuth2ClientContext oauth2ClientContext;

	@Autowired
	protected AuthorizationCodeResourceDetails authorizationCodeResourceDetails;

	@Autowired
	protected ResourceServerProperties resourceServerProperties;

	@Autowired
	protected ApplicationEventPublisher applicationEventPublisher;

	@Autowired
	protected AuthorizationProperties authorizationProperties;

	@Override
	protected void configure(HttpSecurity http) throws Exception {

		final RequestMatcher textHtmlMatcher = new MediaTypeRequestMatcher(
				new BrowserDetectingContentNegotiationStrategy(),
				MediaType.TEXT_HTML);
		final BasicAuthenticationEntryPoint basicAuthenticationEntryPoint = new BasicAuthenticationEntryPoint();
		basicAuthenticationEntryPoint.setRealmName(SecurityConfigUtils.BASIC_AUTH_REALM_NAME);
		basicAuthenticationEntryPoint.afterPropertiesSet();
		final Filter oauthFilter = oauthFilter();
		BasicAuthenticationFilter basicAuthenticationFilter = new BasicAuthenticationFilter(
				providerManager(), basicAuthenticationEntryPoint);
		http.addFilterAfter(oauthFilter, basicAuthenticationFilter.getClass());
		http.addFilterBefore(basicAuthenticationFilter, oauthFilter.getClass());
		http.addFilterBefore(oAuth2AuthenticationProcessingFilter(), basicAuthenticationFilter.getClass());
		this.authorizationProperties.getAuthenticatedPaths().add("/");
		this.authorizationProperties.getAuthenticatedPaths().add(dashboard("/**"));
		this.authorizationProperties.getAuthenticatedPaths().add(this.authorizationProperties.getDashboardUrl());
		this.authorizationProperties.getPermitAllPaths().add(this.authorizationProperties.getDashboardUrl());
		this.authorizationProperties.getPermitAllPaths().add(dashboard("/**"));
		ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry security =

				http.authorizeRequests()
						.antMatchers(this.authorizationProperties.getPermitAllPaths().toArray(new String[0]))
						.permitAll()
						.antMatchers(this.authorizationProperties.getAuthenticatedPaths().toArray(new String[0]))
						.authenticated();
		if (this.authorizationProperties.isEnabled()) {
			security = SecurityConfigUtils.configureSimpleSecurity(security, this.authorizationProperties);
			security.anyRequest().denyAll();
			this.securityStateBean.setAuthorizationEnabled(true);
		}
		else {
			security.anyRequest().authenticated();
			this.securityStateBean.setAuthorizationEnabled(false);
		}
		http.httpBasic().and()
				.logout()
				.logoutSuccessUrl(dashboard("/logout-success-oauth.html"))
				.and().csrf().disable()
				.exceptionHandling()
				.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint(this.authorizationProperties.getLoginProcessingUrl()),
						textHtmlMatcher)
				.defaultAuthenticationEntryPointFor(basicAuthenticationEntryPoint, AnyRequestMatcher.INSTANCE);
		this.securityStateBean.setAuthenticationEnabled(true);
	}

	@Bean
	protected UserInfoTokenServices tokenServices() {
		final UserInfoTokenServices tokenServices = new UserInfoTokenServices(resourceServerProperties.getUserInfoUri(),
				authorizationCodeResourceDetails.getClientId());
		tokenServices.setRestTemplate(oAuth2RestTemplate());
		final AuthoritiesExtractor authoritiesExtractor;
		if (StringUtils.isEmpty(authorizationProperties.getExternalAuthoritiesUrl())) {
			authoritiesExtractor = new DefaultAuthoritiesExtractor();
		} else {
			authoritiesExtractor = new ExternalOauth2ResourceAuthoritiesExtractor(
					oAuth2RestTemplate(), URI.create(authorizationProperties.getExternalAuthoritiesUrl()));
		}
		tokenServices.setAuthoritiesExtractor(authoritiesExtractor);
		return tokenServices;
	}

	@Bean
	protected OAuth2RestTemplate oAuth2RestTemplate() {
		final OAuth2RestTemplate oAuth2RestTemplate = new OAuth2RestTemplate(authorizationCodeResourceDetails,
				oauth2ClientContext);
		return oAuth2RestTemplate;
	}

	@Bean
	protected AuthenticationProvider authenticationProvider() {
		return new ManualOAuthAuthenticationProvider();
	}

	@Bean
	protected ProviderManager providerManager() {
		List<AuthenticationProvider> providers = new ArrayList<>();
		providers.add(this.authenticationProvider());
		ProviderManager providerManager = new ProviderManager(providers);
		return providerManager;
	}

	protected Filter oauthFilter() {
		final OAuth2ClientAuthenticationProcessingFilter oauthFilter = new OAuth2ClientAuthenticationProcessingFilter(
				"/login");
		oauthFilter.setRestTemplate(oAuth2RestTemplate());
		oauthFilter.setTokenServices(tokenServices());
		oauthFilter.setApplicationEventPublisher(this.applicationEventPublisher);
		return oauthFilter;
	}

	protected OAuth2AuthenticationProcessingFilter oAuth2AuthenticationProcessingFilter() {
		final OAuth2AuthenticationProcessingFilter oAuth2AuthenticationProcessingFilter = new OAuth2AuthenticationProcessingFilter();
		oAuth2AuthenticationProcessingFilter.setAuthenticationManager(oauthAuthenticationManager());
		oAuth2AuthenticationProcessingFilter.setStateless(false);
		return oAuth2AuthenticationProcessingFilter;
	}

	@Bean
	public AuthenticationManager oauthAuthenticationManager() {
		final OAuth2AuthenticationManager oauthAuthenticationManager = new OAuth2AuthenticationManager();
		oauthAuthenticationManager.setTokenServices(tokenServices());
		return oauthAuthenticationManager;
	}

	@EventListener
	public void handleOAuth2AuthenticationFailureEvent(
			OAuth2AuthenticationFailureEvent oAuth2AuthenticationFailureEvent) {
		logger.error("An error occurred while accessing an authentication REST resource.",
				oAuth2AuthenticationFailureEvent.getException());
	}

	protected String dashboard(String path) {
		return this.authorizationProperties.getDashboardUrl() + path;
	}

	protected static class BrowserDetectingContentNegotiationStrategy extends HeaderContentNegotiationStrategy {

		@Override
		public List<MediaType> resolveMediaTypes(NativeWebRequest request)
				throws HttpMediaTypeNotAcceptableException {
			final List<MediaType> supportedMediaTypes = super.resolveMediaTypes(request);

			final String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
			if (userAgent != null && userAgent.contains("Mozilla/5.0")
					&& !supportedMediaTypes.contains(MediaType.APPLICATION_JSON)) {

				return Collections.singletonList(MediaType.TEXT_HTML);
			}
			return Collections.singletonList(MediaType.APPLICATION_JSON);
		}
	}
}