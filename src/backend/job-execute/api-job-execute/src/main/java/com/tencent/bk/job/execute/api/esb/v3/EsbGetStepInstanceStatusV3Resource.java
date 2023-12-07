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

package com.tencent.bk.job.execute.api.esb.v3;

import com.tencent.bk.job.common.annotation.EsbAPI;
import com.tencent.bk.job.common.constant.JobCommonHeaders;
import com.tencent.bk.job.common.esb.model.EsbResp;
import com.tencent.bk.job.execute.model.esb.v3.EsbStepInstanceStatusV3DTO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 根据步骤实例 ID 查询步骤执行状态API-V3
 */
@Validated
@RequestMapping("/esb/api/v3")
@RestController
@EsbAPI
public interface EsbGetStepInstanceStatusV3Resource {

    @GetMapping("/get_step_instance_status")
    EsbResp<EsbStepInstanceStatusV3DTO> getStepInstanceStatus(
        @RequestHeader(value = JobCommonHeaders.USERNAME) String username,
        @RequestHeader(value = JobCommonHeaders.APP_CODE) String appCode,
        @RequestParam(value = "bk_scope_type") String scopeType,
        @RequestParam(value = "bk_scope_id") String scopeId,
        @NotNull(message = "{validation.constraints.InvalidJobInstanceId.message}")
        @Min(message = "{validation.constraints.InvalidJobInstanceId.message}", value = 1L)
        @RequestParam(value = "job_instance_id") Long taskInstanceId,
        @NotNull(message = "{validation.constraints.InvalidStepInstanceId.message}")
        @Min(message = "{validation.constraints.InvalidStepInstanceId.message}", value = 1L)
        @RequestParam(value = "step_instance_id") Long stepInstanceId,
        @RequestParam(value = "execute_count", required = false) Integer executeCount,
        @RequestParam(value = "batch", required = false) Integer batch,
        @RequestParam(value = "max_host_num_per_group", required = false) Integer maxHostNumPerGroup,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "search_ip", required = false) String searchIp,
        @RequestParam(value = "status", required = false) Integer status,
        @RequestParam(value = "tag", required = false) String tag);

}
