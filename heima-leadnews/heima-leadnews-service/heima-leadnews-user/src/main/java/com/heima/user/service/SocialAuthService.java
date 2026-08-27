package com.heima.user.service;

import java.util.Map;

public interface SocialAuthService {
     Map<String, Object> getPlatFormUserInfo(String accessToken);

    String getAccessToken2Github(String code);

    String getStraightUid2Weibo(String code);
    public boolean checkUidBound(String uid,String platform);
}
