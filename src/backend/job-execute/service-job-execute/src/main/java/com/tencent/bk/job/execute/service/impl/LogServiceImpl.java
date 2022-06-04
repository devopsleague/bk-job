/*
 * Tencent is pleased to support the open source community by making BK-JOB蓝鲸智云作业平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-JOB蓝鲸智云作业平台 is licensed under the MIT License.
 *
 * License for BK-JOB蓝鲸智云作业平台:
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.tencent.bk.job.execute.service.impl;

import com.tencent.bk.job.common.exception.InternalException;
import com.tencent.bk.job.common.model.InternalResponse;
import com.tencent.bk.job.common.model.dto.HostDTO;
import com.tencent.bk.job.common.util.date.DateUtils;
import com.tencent.bk.job.execute.client.LogServiceResourceClient;
import com.tencent.bk.job.execute.common.constants.FileDistModeEnum;
import com.tencent.bk.job.execute.common.constants.FileDistStatusEnum;
import com.tencent.bk.job.execute.dao.StepInstanceDAO;
import com.tencent.bk.job.execute.engine.consts.AgentTaskStatus;
import com.tencent.bk.job.execute.model.AgentTaskDTO;
import com.tencent.bk.job.execute.model.FileIpLogContent;
import com.tencent.bk.job.execute.model.ScriptHostLogContent;
import com.tencent.bk.job.execute.model.StepInstanceBaseDTO;
import com.tencent.bk.job.execute.service.FileAgentTaskService;
import com.tencent.bk.job.execute.service.LogService;
import com.tencent.bk.job.execute.service.ScriptAgentTaskService;
import com.tencent.bk.job.logsvr.consts.FileTaskModeEnum;
import com.tencent.bk.job.logsvr.consts.LogTypeEnum;
import com.tencent.bk.job.logsvr.model.service.ServiceBatchSaveLogRequest;
import com.tencent.bk.job.logsvr.model.service.ServiceFileLogQueryRequest;
import com.tencent.bk.job.logsvr.model.service.ServiceFileTaskLogDTO;
import com.tencent.bk.job.logsvr.model.service.ServiceHostLogDTO;
import com.tencent.bk.job.logsvr.model.service.ServiceHostLogsDTO;
import com.tencent.bk.job.logsvr.model.service.ServiceSaveLogRequest;
import com.tencent.bk.job.logsvr.model.service.ServiceScriptLogDTO;
import com.tencent.bk.job.logsvr.model.service.ServiceScriptLogQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LogServiceImpl implements LogService {
    private final LogServiceResourceClient logServiceResourceClient;
    private final StepInstanceDAO stepInstanceDAO;
    private final ScriptAgentTaskService scriptAgentTaskService;
    private final FileAgentTaskService fileAgentTaskService;

    @Autowired
    public LogServiceImpl(LogServiceResourceClient logServiceResourceClient,
                          StepInstanceDAO stepInstanceDAO,
                          ScriptAgentTaskService scriptAgentTaskService,
                          FileAgentTaskService fileAgentTaskService) {
        this.logServiceResourceClient = logServiceResourceClient;
        this.stepInstanceDAO = stepInstanceDAO;
        this.scriptAgentTaskService = scriptAgentTaskService;
        this.fileAgentTaskService = fileAgentTaskService;
    }

    @Override
    public ServiceScriptLogDTO buildSystemScriptLog(HostDTO host, String content, int offset, Long logTimeInMillSeconds) {
        String logDateTime;
        if (logTimeInMillSeconds != null) {
            logDateTime = DateUtils.formatUnixTimestamp(logTimeInMillSeconds, ChronoUnit.MILLIS,
                "yyyy-MM-dd HH:mm:ss", ZoneId.systemDefault());
        } else {
            logDateTime = DateUtils.formatUnixTimestamp(System.currentTimeMillis(), ChronoUnit.MILLIS,
                "yyyy-MM-dd HH:mm:ss", ZoneId.systemDefault());
        }
        String logContentWithDateTime = "[" + logDateTime + "] " + content + "\n";
        int length = logContentWithDateTime.getBytes(StandardCharsets.UTF_8).length;
        return new ServiceScriptLogDTO(host, offset + length, logContentWithDateTime);
    }

    @Override
    public void writeScriptLog(String jobCreateDate, long stepInstanceId, int executeCount, Integer batch,
                               ServiceScriptLogDTO scriptLog) {
        if (scriptLog == null || StringUtils.isEmpty(scriptLog.getContent())) {
            return;
        }
        ServiceSaveLogRequest request = new ServiceSaveLogRequest();
        request.setStepInstanceId(stepInstanceId);
        request.setExecuteCount(executeCount);
        request.setBatch(batch);
        request.setIp(scriptLog.getCloudIp());
        request.setJobCreateDate(jobCreateDate);
        request.setScriptLog(scriptLog);
        request.setLogType(LogTypeEnum.SCRIPT.getValue());
        InternalResponse resp = logServiceResourceClient.saveLog(request);
        if (!resp.isSuccess()) {
            log.error("Write log content fail, stepInstanceId:{}, executeCount:{}, batch: {}",
                stepInstanceId, executeCount, batch);
            throw new InternalException(resp.getCode());
        }
    }

    @Override
    public void batchWriteScriptLog(String jobCreateDate, long stepInstanceId, int executeCount, Integer batch,
                                    List<ServiceScriptLogDTO> scriptLogs) {
        if (CollectionUtils.isEmpty(scriptLogs)) {
            return;
        }
        ServiceBatchSaveLogRequest request = new ServiceBatchSaveLogRequest();
        request.setJobCreateDate(jobCreateDate);
        request.setLogType(LogTypeEnum.SCRIPT.getValue());
        List<ServiceHostLogDTO> logs = scriptLogs.stream()
            .map(scriptLog -> buildServiceLogDTO(stepInstanceId, executeCount, batch, scriptLog))
            .collect(Collectors.toList());
        request.setLogs(logs);
        InternalResponse resp = logServiceResourceClient.saveLogs(request);
        if (!resp.isSuccess()) {
            log.error("Batch write log content fail, stepInstanceId:{}, executeCount:{}, batch: {}",
                stepInstanceId, executeCount, batch);
            throw new InternalException(resp.getCode());
        }
    }

    private ServiceHostLogDTO buildServiceLogDTO(long stepInstanceId, int executeCount, Integer batch,
                                                 ServiceScriptLogDTO scriptLog) {
        ServiceHostLogDTO logDTO = new ServiceHostLogDTO();
        logDTO.setStepInstanceId(stepInstanceId);
        logDTO.setExecuteCount(executeCount);
        logDTO.setBatch(batch);
        logDTO.setHostId(scriptLog.getHostId());
        logDTO.setIp(scriptLog.getCloudIp());
        logDTO.setScriptLog(scriptLog);
        return logDTO;
    }

    @Override
    public ScriptHostLogContent getScriptHostLogContent(long stepInstanceId, int executeCount, Integer batch,
                                                        HostDTO host) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        // 如果存在重试，那么该ip可能是之前已经执行过的，查询日志的时候需要获取到对应的executeCount
        int actualExecuteCount = executeCount;
        AgentTaskDTO agentTask = scriptAgentTaskService.getAgentTaskByHost(stepInstanceId, executeCount,
            batch, host);
        if (agentTask == null) {
            return null;
        }
        if (agentTask.getStatus() == AgentTaskStatus.LAST_SUCCESS.getValue()) {
            actualExecuteCount = scriptAgentTaskService.getActualSuccessExecuteCount(stepInstanceId,
                agentTask.getBatch(), host);
        }
        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        InternalResponse<ServiceHostLogDTO> resp;
        if (host.getHostId() != null) {
            resp = logServiceResourceClient.getScriptHostLogByHostId(taskCreateDateStr,
                stepInstanceId, actualExecuteCount, host.getHostId(), batch);
        } else {
            resp = logServiceResourceClient.getScriptHostLogByIp(taskCreateDateStr,
                stepInstanceId, actualExecuteCount, host.getIp(), batch);
        }
        if (!resp.isSuccess()) {
            log.error("Get script log content by host error, stepInstanceId={}, executeCount={}, batch={}, host={}",
                stepInstanceId, actualExecuteCount, batch, host);
            throw new InternalException(resp.getCode());
        }
        return convertToScriptIpLogContent(resp.getData(), agentTask);
    }

    private ScriptHostLogContent convertToScriptIpLogContent(ServiceHostLogDTO logDTO, AgentTaskDTO gseTaskIpLog) {
        if (logDTO == null) {
            return null;
        }
        int ipStatus = gseTaskIpLog.getStatus();
        boolean isFinished =
            ipStatus != AgentTaskStatus.RUNNING.getValue() && ipStatus != AgentTaskStatus.WAITING.getValue();
        String scriptContent = logDTO.getScriptLog() != null ?
            logDTO.getScriptLog().getContent() : "";
        return new ScriptHostLogContent(logDTO.getStepInstanceId(), logDTO.getExecuteCount(), logDTO.getHostId(),
            logDTO.getIp(), scriptContent, isFinished);
    }

    @Override
    public List<ScriptHostLogContent> batchGetScriptHostLogContent(String jobCreateDateStr,
                                                                   long stepInstanceId,
                                                                   int executeCount,
                                                                   Integer batch,
                                                                   List<HostDTO> hosts) {

        ServiceScriptLogQueryRequest query = new ServiceScriptLogQueryRequest();
        query.setIps(hosts.stream().map(HostDTO::toCloudIp).collect(Collectors.toList()));
        query.setBatch(batch);
        InternalResponse<List<ServiceHostLogDTO>> resp =
            logServiceResourceClient.listScriptLogs(jobCreateDateStr, stepInstanceId, executeCount, query);
        if (!resp.isSuccess()) {
            log.error("Get script log content by ips error, stepInstanceId={}, executeCount={}, batch={}, ips={}",
                stepInstanceId, executeCount, batch, hosts);
            throw new InternalException(resp.getCode());
        }
        if (CollectionUtils.isEmpty(resp.getData())) {
            return Collections.emptyList();
        }
        return resp.getData().stream().map(logDTO -> {
            String scriptContent = logDTO.getScriptLog() != null ?
                logDTO.getScriptLog().getContent() : "";
            return new ScriptHostLogContent(logDTO.getStepInstanceId(), logDTO.getExecuteCount(), logDTO.getHostId(),
                logDTO.getIp(), scriptContent, true);
        }).collect(Collectors.toList());

    }

    @Override
    public FileIpLogContent getFileIpLogContent(long stepInstanceId, int executeCount, Integer batch, HostDTO host,
                                                Integer mode) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        // 如果存在重试，那么该ip可能是之前已经执行过的，查询日志的时候需要获取到对应的executeCount
        int actualExecuteCount = executeCount;
        AgentTaskDTO agentTask = fileAgentTaskService.getAgentTask(stepInstanceId, executeCount, batch,
            FileTaskModeEnum.getFileTaskMode(mode), host);
        if (agentTask == null) {
            return null;
        }
        if (agentTask.getStatus() == AgentTaskStatus.LAST_SUCCESS.getValue()) {
            actualExecuteCount = fileAgentTaskService.getActualSuccessExecuteCount(stepInstanceId,
                agentTask.getBatch(), agentTask.getFileTaskMode(), host);
        }

        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        InternalResponse<ServiceHostLogDTO> resp;
        if (host.getHostId() != null) {
            resp = logServiceResourceClient.getFileHostLogByHostId(taskCreateDateStr,
                stepInstanceId, actualExecuteCount, host.getHostId(), mode, batch);
        } else {
            resp = logServiceResourceClient.getFileHostLogByIp(taskCreateDateStr,
                stepInstanceId, actualExecuteCount, host.getIp(), mode, batch);
        }

        if (!resp.isSuccess()) {
            log.error("Get file log content by ip error, stepInstanceId={}, executeCount={}, batch={}, host={}",
                stepInstanceId, actualExecuteCount, batch, host);
            throw new InternalException(resp.getCode());
        }
        List<ServiceFileTaskLogDTO> fileTaskLogs = (resp.getData() == null) ? null : resp.getData().getFileTaskLogs();
        int ipStatus = agentTask.getStatus();
        boolean isFinished =
            (ipStatus != AgentTaskStatus.RUNNING.getValue() && ipStatus != AgentTaskStatus.WAITING.getValue()) ||
                isAllFileTasksFinished(fileTaskLogs);
        return new FileIpLogContent(stepInstanceId, executeCount, null, fileTaskLogs, isFinished);
    }

    private boolean isAllFileTasksFinished(List<ServiceFileTaskLogDTO> fileTaskLogs) {
        if (CollectionUtils.isEmpty(fileTaskLogs)) {
            return false;
        }
        return fileTaskLogs.stream().noneMatch(this::isFileTaskNotFinished);
    }

    private boolean isFileTaskNotFinished(ServiceFileTaskLogDTO fileTaskLog) {
        FileDistStatusEnum status = FileDistStatusEnum.getFileDistStatus(fileTaskLog.getStatus());
        if (status == null) {
            return true;
        }
        return status == FileDistStatusEnum.DOWNLOADING || status == FileDistStatusEnum.UPLOADING
            || status == FileDistStatusEnum.PULLING || status == FileDistStatusEnum.WAITING;
    }

    @Override
    public List<ServiceFileTaskLogDTO> getFileLogContentByTaskIds(long stepInstanceId, int executeCount, Integer batch,
                                                                  List<String> taskIds) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        InternalResponse<ServiceHostLogDTO> resp = logServiceResourceClient.listFileHostLogsByTaskIds(
            taskCreateDateStr, stepInstanceId, executeCount, batch, taskIds);
        if (!resp.isSuccess()) {
            log.error("Get file log content by ids error, stepInstanceId={}, executeCount={}, batch={}, taskIds={}",
                stepInstanceId, executeCount, batch, taskIds);
            throw new InternalException(resp.getCode());
        }
        if (resp.getData() == null) {
            return Collections.emptyList();
        }
        return resp.getData().getFileTaskLogs();
    }

    @Override
    public List<ServiceFileTaskLogDTO> batchGetFileSourceIpLogContent(long stepInstanceId,
                                                                      int executeCount,
                                                                      Integer batch) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        InternalResponse<List<ServiceFileTaskLogDTO>> resp = logServiceResourceClient.listFileHostLogs(
            taskCreateDateStr, stepInstanceId, executeCount, batch, FileDistModeEnum.UPLOAD.getValue(), null, null);
        if (!resp.isSuccess()) {
            log.error("Get file source log content error, stepInstanceId={}, executeCount={}, batch={}",
                stepInstanceId, executeCount, batch);
            return Collections.emptyList();
        }
        return resp.getData();
    }

    @Override
    public ServiceHostLogsDTO batchGetFileIpLogContent(long stepInstanceId, int executeCount, Integer batch,
                                                       List<HostDTO> hosts) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        ServiceFileLogQueryRequest request = new ServiceFileLogQueryRequest();
        request.setStepInstanceId(stepInstanceId);
        request.setExecuteCount(executeCount);
        request.setBatch(batch);
        request.setJobCreateDate(taskCreateDateStr);
        request.setIps(hosts.stream().map(HostDTO::toCloudIp).collect(Collectors.toList()));

        InternalResponse<ServiceHostLogsDTO> resp = logServiceResourceClient.listFileHostLogs(request);
        if (!resp.isSuccess()) {
            log.error("Get file log content error, request={}", request);
            return null;
        }
        return resp.getData();
    }

    @Override
    public List<HostDTO> getIpsByContentKeyword(long stepInstanceId, int executeCount, Integer batch,
                                                String keyword) {
        StepInstanceBaseDTO stepInstance = stepInstanceDAO.getStepInstanceBase(stepInstanceId);
        String taskCreateDateStr = DateUtils.formatUnixTimestamp(stepInstance.getCreateTime(), ChronoUnit.MILLIS,
            "yyyy_MM_dd", ZoneId.of("UTC"));
        InternalResponse<List<HostDTO>> resp = logServiceResourceClient.getIpsByKeyword(taskCreateDateStr,
            stepInstanceId, executeCount, batch, keyword);

        if (!resp.isSuccess()) {
            log.error("Search ips by keyword error, stepInstanceId={}, executeCount={}, keyword={}", stepInstanceId,
                executeCount, keyword);
            throw new InternalException(resp.getCode());
        }
        return resp.getData();
    }

    @Override
    public void writeFileLogWithTimestamp(long jobCreateTime,
                                          long stepInstanceId,
                                          int executeCount,
                                          Integer batch,
                                          HostDTO host,
                                          ServiceHostLogDTO executionLog,
                                          Long logTimeInMillSeconds) {

        String logDateTime = "[";
        if (logTimeInMillSeconds != null) {
            logDateTime += DateUtils.formatUnixTimestamp(logTimeInMillSeconds, ChronoUnit.MILLIS, "yyyy-MM-dd " +
                "HH:mm:ss", ZoneId.systemDefault());
        } else {
            logDateTime += DateUtils.formatUnixTimestamp(System.currentTimeMillis(), ChronoUnit.MILLIS, "yyyy-MM-dd " +
                "HH:mm:ss", ZoneId.systemDefault());
        }
        logDateTime += "] ";
        for (ServiceFileTaskLogDTO fileTaskLog : executionLog.getFileTaskLogs()) {
            fileTaskLog.setContent(logDateTime + fileTaskLog.getContent() + "\n");
        }
        ServiceSaveLogRequest request = new ServiceSaveLogRequest();
        request.setLogType(LogTypeEnum.FILE.getValue());
        request.setStepInstanceId(stepInstanceId);
        request.setExecuteCount(executeCount);
        request.setBatch(batch);
        request.setIp(host.getIp());
        request.setHostId(host.getHostId());
        request.setJobCreateDate(DateUtils.formatUnixTimestamp(jobCreateTime, ChronoUnit.MILLIS, "yyyy_MM_dd",
            ZoneId.of("UTC")));
        request.setFileTaskLogs(executionLog.getFileTaskLogs());
        logServiceResourceClient.saveLog(request);
    }

    @Override
    public void writeFileLogs(long jobCreateTime, List<ServiceHostLogDTO> fileLogs) {
        ServiceBatchSaveLogRequest request = new ServiceBatchSaveLogRequest();
        request.setJobCreateDate(DateUtils.formatUnixTimestamp(jobCreateTime, ChronoUnit.MILLIS, "yyyy_MM_dd",
            ZoneId.of("UTC")));
        request.setLogs(fileLogs);
        request.setLogType(LogTypeEnum.FILE.getValue());
        logServiceResourceClient.saveLogs(request);
    }
}
