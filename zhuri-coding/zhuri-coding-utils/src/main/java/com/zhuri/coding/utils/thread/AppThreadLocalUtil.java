package com.heima.utils.thread;

import com.heima.model.user.pojos.ApUser;

public class AppThreadLocalUtil {

    private final static ThreadLocal<ApUser> WM_USER_THREAD_LOCAL = new ThreadLocal<>();

    //存入线程中
    public static void setUser(ApUser apUser){
        WM_USER_THREAD_LOCAL.set(apUser);
    }

    //从线程中获取
    public static ApUser getUser(){
        ApUser apUser = WM_USER_THREAD_LOCAL.get();
//        System.out.println("线程中的用户信息："+apUser);
        return apUser;
    }

    //清理
    public static void clear(){
        WM_USER_THREAD_LOCAL.remove();
    }

}
