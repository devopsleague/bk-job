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

package com.tencent.bk.job.manage.api.web.impl;

import com.tencent.bk.job.common.iam.constant.ActionId;
import com.tencent.bk.job.common.iam.constant.ResourceTypeEnum;
import com.tencent.bk.job.common.iam.exception.PermissionDeniedException;
import com.tencent.bk.job.common.iam.model.AuthResult;
import com.tencent.bk.job.common.iam.service.AuthService;
import com.tencent.bk.job.common.model.Response;
import com.tencent.bk.job.manage.api.web.WebNotifyResource;
import com.tencent.bk.job.manage.model.inner.ServiceNotificationDTO;
import com.tencent.bk.job.manage.model.web.request.notify.NotifyPoliciesCreateUpdateReq;
import com.tencent.bk.job.manage.model.web.vo.notify.PageTemplateVO;
import com.tencent.bk.job.manage.model.web.vo.notify.RoleVO;
import com.tencent.bk.job.manage.model.web.vo.notify.TriggerPolicyVO;
import com.tencent.bk.job.manage.model.web.vo.notify.UserVO;
import com.tencent.bk.job.manage.service.LocalPermissionService;
import com.tencent.bk.job.manage.service.NotifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.tencent.bk.job.common.constant.ErrorCode.PERMISSION_DENIED;

@RestController
@Slf4j
public class WebNotifyResourceImpl implements WebNotifyResource {

    private final NotifyService notifyService;
    private final LocalPermissionService localPermissionService;
    private final AuthService authService;

    @Autowired
    public WebNotifyResourceImpl(NotifyService notifyService, LocalPermissionService localPermissionService,
                                 AuthService authService) {
        this.notifyService = notifyService;
        this.localPermissionService = localPermissionService;
        this.authService = authService;
    }

    @Override
    public Response<List<TriggerPolicyVO>> listAppDefaultNotifyPolicies(String username, Long appId) {
        return Response.buildSuccessResp(notifyService.listAppDefaultNotifyPolicies(username, appId));
    }

    @Override
    public Response<Long> saveAppDefaultNotifyPolicies(
        String username,
        Long appId,
        NotifyPoliciesCreateUpdateReq createUpdateReq
    ) {
        AuthResult authResult = authService.auth(true, username, ActionId.NOTIFICATION_SETTING,
            ResourceTypeEnum.BUSINESS, appId.toString(), null);
        if (!authResult.isPass()) {
            throw new PermissionDeniedException(authResult);
        }
        return Response.buildSuccessResp(notifyService.saveAppDefaultNotifyPolicies(
            username, appId, createUpdateReq));
    }

    @Override
    public Response<PageTemplateVO> getPageTemplate(String username) {
        return Response.buildSuccessResp(notifyService.getPageTemplate(username));
    }

    @Override
    public Response<List<RoleVO>> listRoles(String username) {
        return Response.buildSuccessResp(notifyService.listRole(username));
    }

    @Override
    public Response<List<UserVO>> listUsers(
        String username,
        String prefixStr,
        Long offset,
        Long limit
    ) {
        return Response.buildSuccessResp(notifyService.listUsers(username, prefixStr, offset, limit, true));
    }

    @Override
    public Response sendNotification(String username, ServiceNotificationDTO notification) {
        if (localPermissionService.isAdmin(username)) {
            return Response.buildSuccessResp(notifyService.sendSimpleNotification(notification));
        } else {
            return Response.buildCommonFailResp(PERMISSION_DENIED);
        }
    }
}
