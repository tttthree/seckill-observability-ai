package com.hmdp.constant;

/**
 * @author 虎哥
 */
public abstract class RegexPatterns {
    /**
     * 手机号正则   第一位固定是 1  第二位开始分组 31x/32x... 或 80x/81x... 45x/47x/49x 50x-53x、55x-59x 166 170-173/175-178 198/199
     *  后面 8 位任意数字   总长度固定：1 + 2 + 8 = 11 位
     */
    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";
    /**
     * 邮箱正则 字母数字下划线横线
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    /**
     * 密码正则。4~32位的字母、数字、下划线
     * \\w = [a-zA-Z0-9_] 字母、数字、下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    /**
     * 验证码正则, 6位数字或字母
     * [a-zA-Z\\d]{6} 字母或数字，固定 6 位
     * \\d = [0-9]
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
