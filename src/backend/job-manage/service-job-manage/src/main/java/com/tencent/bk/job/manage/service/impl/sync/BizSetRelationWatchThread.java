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

package com.tencent.bk.job.manage.service.impl.sync;

import com.tencent.bk.job.common.cc.model.req.ResourceWatchReq;
import com.tencent.bk.job.common.cc.model.result.BizSetRelationEventDetail;
import com.tencent.bk.job.common.cc.model.result.ResourceEvent;
import com.tencent.bk.job.common.cc.model.result.ResourceWatchResult;
import com.tencent.bk.job.common.cc.sdk.IBizSetCmdbClient;
import com.tencent.bk.job.common.constant.ResourceScopeTypeEnum;
import com.tencent.bk.job.common.model.dto.ApplicationDTO;
import com.tencent.bk.job.common.model.dto.ResourceScope;
import com.tencent.bk.job.common.redis.util.LockUtils;
import com.tencent.bk.job.common.redis.util.RedisKeyHeartBeatThread;
import com.tencent.bk.job.common.util.TimeUtil;
import com.tencent.bk.job.common.util.ip.IpUtils;
import com.tencent.bk.job.common.util.json.JsonUtils;
import com.tencent.bk.job.manage.dao.ApplicationDAO;
import com.tencent.bk.job.manage.manager.app.ApplicationCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StopWatch;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 业务集与业务关系事件监听
 */
@Slf4j
public class BizSetRelationWatchThread extends Thread {

    private static final String REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_LOCK
        = "resource-watch-biz-set-relation-job-lock";
    private static final String machineIp = IpUtils.getFirstMachineIP();

    static {
        try {
            //进程重启首先尝试释放上次加上的锁避免死锁
            LockUtils.releaseDistributedLock(REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_LOCK, machineIp);
        } catch (Throwable t) {
            log.info("Redis key:" + REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_LOCK + " does not need to be " +
                "released, " +
                "ignore");
        }
    }

    private final RedisTemplate<String, String> redisTemplate;
    private final ApplicationCache applicationCache;
    private final IBizSetCmdbClient bizSetCmdbClient;
    private final ApplicationDAO applicationDAO;
    private final AtomicBoolean bizSetRelationWatchFlag = new AtomicBoolean(true);

    public BizSetRelationWatchThread(RedisTemplate<String, String> redisTemplate,
                                     ApplicationCache applicationCache,
                                     IBizSetCmdbClient bizSetCmdbClient,
                                     ApplicationDAO applicationDAO) {
        this.redisTemplate = redisTemplate;
        this.applicationCache = applicationCache;
        this.bizSetCmdbClient = bizSetCmdbClient;
        this.applicationDAO = applicationDAO;
        this.setName("[" + getId() + "]-BizSetRelationWatchThread-");
    }

    public void setWatchFlag(boolean value) {
        bizSetRelationWatchFlag.set(value);
    }

    @Override
    public void run() {
        log.info("BizSetRelationWatch arranged");
        while (true) {
            String cursor = null;
            // 从10分钟前开始watch
            long startTime = System.currentTimeMillis() / 1000 - 10 * 60;
            try {
                boolean lockGotten = LockUtils.tryGetDistributedLock(REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_LOCK,
                    machineIp, 50);
                if (!lockGotten) {
                    log.info("bizSetRelationWatch lock not gotten, wait 100ms and retry");
                    try {
                        sleep(100);
                    } catch (InterruptedException e) {
                        log.warn("Sleep interrupted", e);
                    }
                    continue;
                }
                String REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_RUNNING_MACHINE =
                    "resource-watch-biz-set-relation-job-running-machine";
                String runningMachine =
                    redisTemplate.opsForValue().get(REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_RUNNING_MACHINE);
                if (StringUtils.isNotBlank(runningMachine)) {
                    //已有bizSetRelationWatch线程在跑，不再重复Watch
                    log.info("bizSetRelationWatch thread already running on {}", runningMachine);
                    try {
                        sleep(30000);
                    } catch (InterruptedException e) {
                        log.warn("Sleep interrupted", e);
                    }
                    continue;
                }
                // 开一个心跳子线程，维护当前机器正在WatchResource的状态
                RedisKeyHeartBeatThread bizSetRelationWatchRedisKeyHeartBeatThread = new RedisKeyHeartBeatThread(
                    redisTemplate,
                    REDIS_KEY_RESOURCE_WATCH_BIZ_SET_RELATION_JOB_RUNNING_MACHINE,
                    machineIp,
                    3000L,
                    2000L
                );
                bizSetRelationWatchRedisKeyHeartBeatThread.setName("[" + bizSetRelationWatchRedisKeyHeartBeatThread.getId() +
                    "]-bizSetRelationWatchRedisKeyHeartBeatThread");
                bizSetRelationWatchRedisKeyHeartBeatThread.start();
                log.info("start watch biz_set relation resource at {},{}", TimeUtil.getCurrentTimeStr("HH:mm:ss"),
                    System.currentTimeMillis());
                StopWatch watch = new StopWatch("bizSetRelationWatch");
                watch.start("total");
                try {
                    ResourceWatchResult<BizSetRelationEventDetail> bizSetRelationWatchResult;
                    while (bizSetRelationWatchFlag.get()) {
                        if (cursor == null) {
                            log.info("Start watch from startTime:{}", TimeUtil.formatTime(startTime * 1000));
                            bizSetRelationWatchResult = bizSetCmdbClient.getBizSetRelationEvents(startTime, cursor);
                        } else {
                            bizSetRelationWatchResult = bizSetCmdbClient.getBizSetRelationEvents(null, cursor);
                        }
                        log.info("bizSetRelationWatchResult={}", JsonUtils.toJson(bizSetRelationWatchResult));
                        cursor = handleBizSetRelationWatchResult(bizSetRelationWatchResult);
                        // 1s/watch一次
                        sleep(1000);
                    }
                } catch (Throwable t) {
                    log.error("bizSetRelationWatch thread fail", t);
                    // 重置Watch起始位置为10分钟前
                    startTime = System.currentTimeMillis() / 1000 - 10 * 60;
                    cursor = null;
                } finally {
                    bizSetRelationWatchRedisKeyHeartBeatThread.setRunFlag(false);
                    watch.stop();
                    log.info("bizSetRelationWatch time consuming:" + watch.toString());
                }
            } catch (Throwable t) {
                log.error("BizSetRelationWatchThread quit unexpectedly", t);
                startTime = System.currentTimeMillis() / 1000 - 10 * 60;
                cursor = null;
            } finally {
                try {
                    do {
                        // 5s/重试一次
                        sleep(5000);
                    } while (!bizSetRelationWatchFlag.get());
                } catch (InterruptedException e) {
                    log.error("sleep interrupted", e);
                }
            }
        }
    }


    private String handleBizSetRelationWatchResult(
        ResourceWatchResult<BizSetRelationEventDetail> bizSetRelationWatchResult) {
        String cursor = null;
        boolean isWatched = bizSetRelationWatchResult.getWatched();
        if (isWatched) {
            List<ResourceEvent<BizSetRelationEventDetail>> events = bizSetRelationWatchResult.getEvents();
            //解析事件，进行处理
            for (ResourceEvent<BizSetRelationEventDetail> event : events) {
                handleEvent(event);
            }
            if (events.size() > 0) {
                log.info("events.size={},events={}", events.size(), JsonUtils.toJson(events));
                cursor = events.get(events.size() - 1).getCursor();
                log.info("refresh cursor(success):{}", cursor);
            } else {
                log.info("events.size==0");
            }
        } else {
            // 只有一个无实际意义的事件，用于换取bk_cursor
            List<ResourceEvent<BizSetRelationEventDetail>> events = bizSetRelationWatchResult.getEvents();
            if (events != null && events.size() > 0) {
                cursor = events.get(0).getCursor();
                log.info("refresh cursor(fail):{}", cursor);
            } else {
                log.warn("CMDB event error:no refresh event data when watched==false");
            }
        }
        return cursor;
    }

    private void handleEvent(ResourceEvent<BizSetRelationEventDetail> event) {
        String eventType = event.getEventType();
        switch (eventType) {
            case ResourceWatchReq.EVENT_TYPE_UPDATE:
                try {
                    Long bizSetId = event.getDetail().getBizSetId();
                    List<Long> latestSubBizIds = event.getDetail().getBizIds();
                    ApplicationDTO cacheApplication =
                        applicationDAO.getAppByScopeIncludingDeleted(
                            new ResourceScope(ResourceScopeTypeEnum.BIZ_SET.getValue(), String.valueOf(bizSetId))
                        );
                    if (cacheApplication == null || cacheApplication.isDeleted()) {
                        return;
                    }
                    String maintainers;
                    if (CollectionUtils.isEmpty(latestSubBizIds)) {
                        maintainers = null;
                    } else {
                        maintainers = latestSubBizIds.stream().map(String::valueOf).collect(Collectors.joining(";"));
                    }
                    cacheApplication.setSubAppIds(latestSubBizIds);
                    applicationDAO.updateMaintainers(cacheApplication.getId(), maintainers);
                    applicationCache.addOrUpdateApp(cacheApplication);
                } catch (Throwable t) {
                    log.error("Handle biz_set_relation event fail", t);
                }
                break;
            default:
                log.info("No need to handle event: {}", event);
                break;
        }
    }

}
