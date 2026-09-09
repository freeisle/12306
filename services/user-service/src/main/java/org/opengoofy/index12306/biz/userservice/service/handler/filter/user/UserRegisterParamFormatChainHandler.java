/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.userservice.service.handler.filter.user;

import cn.hutool.core.lang.Validator;
import org.opengoofy.index12306.biz.userservice.common.enums.UserRegisterErrorCodeEnum;
import org.opengoofy.index12306.biz.userservice.dto.req.UserRegisterReqDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

/**
 * 用户注册参数格式检验
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
public final class UserRegisterParamFormatChainHandler implements UserRegisterCreateChainFilter<UserRegisterReqDTO> {

    @Override
    public void handler(UserRegisterReqDTO requestParam) {
        if (!Validator.isMobile(requestParam.getPhone())) {
            throw new ClientException(UserRegisterErrorCodeEnum.PHONE_FORMAT_ERROR);
        }
        if (!Validator.isCitizenId(requestParam.getIdCard())) {
            throw new ClientException(UserRegisterErrorCodeEnum.ID_CARD_FORMAT_ERROR);
        }
        if (!Validator.isEmail(requestParam.getMail()))
            throw new ClientException(UserRegisterErrorCodeEnum.MAIL_FORMAT_ERROR);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
