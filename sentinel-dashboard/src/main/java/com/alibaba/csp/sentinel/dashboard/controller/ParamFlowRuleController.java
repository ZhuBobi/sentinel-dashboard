/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.dashboard.controller;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import com.alibaba.csp.sentinel.dashboard.client.CommandNotFoundException;
import com.alibaba.csp.sentinel.dashboard.client.SentinelApiClient;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity;
import com.alibaba.csp.sentinel.dashboard.discovery.AppManagement;
import com.alibaba.csp.sentinel.dashboard.discovery.MachineInfo;
import com.alibaba.csp.sentinel.dashboard.auth.AuthService;
import com.alibaba.csp.sentinel.dashboard.auth.AuthService.AuthUser;
import com.alibaba.csp.sentinel.dashboard.auth.AuthService.PrivilegeType;
import com.alibaba.csp.sentinel.dashboard.rule.DynamicRuleProvider;
import com.alibaba.csp.sentinel.dashboard.rule.DynamicRulePublisher;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.util.StringUtil;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.SentinelVersion;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.ParamFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.domain.Result;
import com.alibaba.csp.sentinel.dashboard.repository.rule.RuleRepository;
import com.alibaba.csp.sentinel.dashboard.util.VersionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Eric Zhao
 * @since 0.2.1
 */
@RestController
@RequestMapping(value = "/paramFlow")
public class ParamFlowRuleController {

    private final Logger logger = LoggerFactory.getLogger(ParamFlowRuleController.class);

    @Autowired
    private SentinelApiClient sentinelApiClient;
    @Autowired
    private AppManagement appManagement;
    @Autowired
    private RuleRepository<ParamFlowRuleEntity, Long> repository;

    @Autowired
    private AuthService<HttpServletRequest> authService;

    @Autowired
    @Qualifier("paramFlowRuleNacosProvider")
    private DynamicRuleProvider<List<ParamFlowRuleEntity>> ruleProvider;

    @Autowired
    @Qualifier("paramFlowRuleNacosPublisher")
    private DynamicRulePublisher<List<ParamFlowRuleEntity>> rulePublisher;

    private boolean checkIfSupported(String app, String ip, int port) {
        try {
            return Optional.ofNullable(appManagement.getDetailApp(app))
                .flatMap(e -> e.getMachine(ip, port))
                .flatMap(m -> VersionUtils.parseVersion(m.getVersion())
                    .map(v -> v.greaterOrEqual(version020)))
                .orElse(true);
            // If error occurred or cannot retrieve machine info, return true.
        } catch (Exception ex) {
            return true;
        }
    }

    @GetMapping("/rules")
    public Result<List<ParamFlowRuleEntity>> apiQueryAllRulesForMachine(HttpServletRequest request,
                                                                        @RequestParam String app,
                                                                        @RequestParam String ip,
                                                                        @RequestParam Integer port) {
        AuthUser authUser = authService.getAuthUser(request);
        authUser.authTarget(app, PrivilegeType.READ_RULE);
        if (StringUtil.isEmpty(app)) {
            return Result.ofFail(-1, "app cannot be null or empty");
        }
        if (StringUtil.isEmpty(ip)) {
            return Result.ofFail(-1, "ip cannot be null or empty");
        }
        if (port == null || port <= 0) {
            return Result.ofFail(-1, "Invalid parameter: port");
        }
        if (!checkIfSupported(app, ip, port)) {
            return unsupportedVersion();
        }
        try {
//            return sentinelApiClient.fetchParamFlowRulesOfMachine(app, ip, port)
//                .thenApply(repository::saveAll)
//                .thenApply(Result::ofSuccess)
//                .get();
            //从nacos拉取规则
            List<ParamFlowRuleEntity> rules = ruleProvider.getRules(app);
            List<ParamFlowRuleEntity> filterRules = rules.stream().filter(s -> s.getIp().equals(ip) && s.getPort().equals(port)).collect(Collectors.toList());
            filterRules = repository.saveAll(filterRules);
            //保存到sentinel-dashboard内存中
            return Result.ofSuccess(filterRules);
        } catch (ExecutionException ex) {
            logger.error("Error when querying parameter flow rules", ex.getCause());
            if (isNotSupported(ex.getCause())) {
                return unsupportedVersion();
            } else {
                return Result.ofThrowable(-1, ex.getCause());
            }
        } catch (Throwable throwable) {
            logger.error("Error when querying parameter flow rules", throwable);
            return Result.ofFail(-1, throwable.getMessage());
        }
    }

    private boolean isNotSupported(Throwable ex) {
        return ex instanceof CommandNotFoundException;
    }

    @PostMapping("/rule")
    public Result<ParamFlowRuleEntity> apiAddParamFlowRule(HttpServletRequest request,
                                                           @RequestBody ParamFlowRuleEntity entity) {
        AuthUser authUser = authService.getAuthUser(request);
        authUser.authTarget(entity.getApp(), PrivilegeType.WRITE_RULE);
        Result<ParamFlowRuleEntity> checkResult = checkEntityInternal(entity);
        if (checkResult != null) {
            return checkResult;
        }
        if (!checkIfSupported(entity.getApp(), entity.getIp(), entity.getPort())) {
            return unsupportedVersion();
        }
        entity.setId(null);
        entity.getRule().setResource(entity.getResource().trim());
        Date date = new Date();
        entity.setGmtCreate(date);
        entity.setGmtModified(date);
        try {
            entity = repository.save(entity);
            publishRules(entity.getApp(), entity.getIp(), entity.getPort()).get();
            return Result.ofSuccess(entity);
        } catch (ExecutionException ex) {
            logger.error("Error when adding new parameter flow rules", ex.getCause());
            if (isNotSupported(ex.getCause())) {
                return unsupportedVersion();
            } else {
                return Result.ofThrowable(-1, ex.getCause());
            }
        } catch (Throwable throwable) {
            logger.error("Error when adding new parameter flow rules", throwable);
            return Result.ofFail(-1, throwable.getMessage());
        }
    }

    private <R> Result<R> checkEntityInternal(ParamFlowRuleEntity entity) {
        if (entity == null) {
            return Result.ofFail(-1, "bad rule body");
        }
        if (StringUtil.isBlank(entity.getApp())) {
            return Result.ofFail(-1, "app can't be null or empty");
        }
        if (StringUtil.isBlank(entity.getIp())) {
            return Result.ofFail(-1, "ip can't be null or empty");
        }
        if (entity.getPort() == null || entity.getPort() <= 0) {
            return Result.ofFail(-1, "port can't be null");
        }
        if (entity.getRule() == null) {
            return Result.ofFail(-1, "rule can't be null");
        }
        if (StringUtil.isBlank(entity.getResource())) {
            return Result.ofFail(-1, "resource name cannot be null or empty");
        }
        if (entity.getCount() < 0) {
            return Result.ofFail(-1, "count should be valid");
        }
        if (entity.getGrade() != RuleConstant.FLOW_GRADE_QPS) {
            return Result.ofFail(-1, "Unknown mode (blockGrade) for parameter flow control");
        }
        if (entity.getParamIdx() == null || entity.getParamIdx() < 0) {
            return Result.ofFail(-1, "paramIdx should be valid");
        }
        if (entity.getDurationInSec() <= 0) {
            return Result.ofFail(-1, "durationInSec should be valid");
        }
        if (entity.getControlBehavior() < 0) {
            return Result.ofFail(-1, "controlBehavior should be valid");
        }
        return null;
    }

    @PutMapping("/rule/{id}")
    public Result<ParamFlowRuleEntity> apiUpdateParamFlowRule(HttpServletRequest request,
                                                              @PathVariable("id") Long id,
                                                              @RequestBody ParamFlowRuleEntity entity) {
        AuthUser authUser = authService.getAuthUser(request);
        if (id == null || id <= 0) {
            return Result.ofFail(-1, "Invalid id");
        }
        ParamFlowRuleEntity oldEntity = repository.findById(id);
        if (oldEntity == null) {
            return Result.ofFail(-1, "id " + id + " does not exist");
        }
        authUser.authTarget(oldEntity.getApp(), PrivilegeType.WRITE_RULE);
        Result<ParamFlowRuleEntity> checkResult = checkEntityInternal(entity);
        if (checkResult != null) {
            return checkResult;
        }
        if (!checkIfSupported(entity.getApp(), entity.getIp(), entity.getPort())) {
            return unsupportedVersion();
        }
        entity.setId(id);
        Date date = new Date();
        entity.setGmtCreate(oldEntity.getGmtCreate());
        entity.setGmtModified(date);
        try {
            entity = repository.save(entity);
            publishRules(entity.getApp(), entity.getIp(), entity.getPort()).get();
            return Result.ofSuccess(entity);
        } catch (ExecutionException ex) {
            logger.error("Error when updating parameter flow rules, id=" + id, ex.getCause());
            if (isNotSupported(ex.getCause())) {
                return unsupportedVersion();
            } else {
                return Result.ofThrowable(-1, ex.getCause());
            }
        } catch (Throwable throwable) {
            logger.error("Error when updating parameter flow rules, id=" + id, throwable);
            return Result.ofFail(-1, throwable.getMessage());
        }
    }

    @DeleteMapping("/rule/{id}")
    public Result<Long> apiDeleteRule(HttpServletRequest request, @PathVariable("id") Long id) {
        AuthUser authUser = authService.getAuthUser(request);
        if (id == null) {
            return Result.ofFail(-1, "id cannot be null");
        }
        ParamFlowRuleEntity oldEntity = repository.findById(id);
        if (oldEntity == null) {
            return Result.ofSuccess(null);
        }
        authUser.authTarget(oldEntity.getApp(), PrivilegeType.DELETE_RULE);
        try {
            repository.delete(id);
            publishRules(oldEntity.getApp(), oldEntity.getIp(), oldEntity.getPort()).get();
            return Result.ofSuccess(id);
        } catch (ExecutionException ex) {
            logger.error("Error when deleting parameter flow rules", ex.getCause());
            if (isNotSupported(ex.getCause())) {
                return unsupportedVersion();
            } else {
                return Result.ofThrowable(-1, ex.getCause());
            }
        } catch (Throwable throwable) {
            logger.error("Error when deleting parameter flow rules", throwable);
            return Result.ofFail(-1, throwable.getMessage());
        }
    }

    private CompletableFuture<Void> publishRules(String app, String ip, Integer port) {
        List<ParamFlowRuleEntity> rules = repository.findAllByMachine(MachineInfo.of(app, ip, port));
        try{
            rulePublisher.publish(app,rules);
        } catch (Exception exception){
            logger.error("规则发布到Ncaos出现异常,原因：【{}】",exception.getMessage(),exception);
            exception.printStackTrace();
        }
        return sentinelApiClient.setParamFlowRuleOfMachine(app, ip, port, rules);
    }

    private <R> Result<R> unsupportedVersion() {
        return Result.ofFail(4041,
            "Sentinel client not supported for parameter flow control (unsupported version or dependency absent)");
    }

    private final SentinelVersion version020 = new SentinelVersion().setMinorVersion(2);
}
