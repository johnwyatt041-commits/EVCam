package com.kooo.evcam.remote.core;

/**
 * 聊天标识接口
 * 统一封装不同平台的聊天 ID
 */
public interface ChatIdentifier {
    
    /**
     * 获取字符串形式的 ID（用于日志和存储）
     */
    String getId();
    
    /**
     * 获取所属平台
     */
    RemotePlatform getPlatform();
    
    // ==================== 静态工厂方法 ====================
    
    /**
     * 创建飞书聊天标识
     */
    static FeishuChatId feishu(String chatId) {
        return new FeishuChatId(chatId);
    }
    
    // ==================== 具体实现类 ====================
    
    /**
     * 飞书聊天标识
     */
    class FeishuChatId implements ChatIdentifier {
        private final String chatId;
        
        public FeishuChatId(String chatId) {
            this.chatId = chatId;
        }
        
        @Override
        public String getId() {
            return chatId;
        }
        
        @Override
        public RemotePlatform getPlatform() {
            return RemotePlatform.FEISHU;
        }
        
        public String getChatId() {
            return chatId;
        }
        
        @Override
        public String toString() {
            return "Feishu[" + chatId + "]";
        }
    }
}
