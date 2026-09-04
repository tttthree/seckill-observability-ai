package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;

/**
 * 存储当前用户的登录信息
 * UserHolder 基于 ThreadLocal 实现，用于保存当前线程中的用户信息，方便在业务层获取当前登录用户，避免层层传递 userId
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
