<template>
  <div class="container">
    <a-row class="row" :gutter="[10, 10]">
      <a-col :xs="24" :sm="24" :md="12" :lg="10" :xl="8">
        <a-card class="card chat">
          <template #title>
            <div style="display: flex; align-items: center; justify-content: space-between;">
              <label style="font-size: 25px">🌍 旅游推荐助手</label>
              <a-button 
                type="primary" 
                size="small" 
                @click="clearChat"
                style="margin-left: 10px;"
              >
                清空对话
              </a-button>
            </div>
          </template>
          <div class="flex-grow">
            <a-card class="chat-body">
              <MessageList :list="messageInfo.list"></MessageList>
              <div
                id="chat-body-id"
                style="height: 5px; margin-top: 20px"
              ></div>
            </a-card>
          </div>
          <a-row class="footer" :gutter="10">
            <a-col :span="20">
              <a-input
                @keydown.enter="sendMessage"
                v-model:value="question"
                placeholder="请输入您的旅游问题..."
              ></a-input>
            </a-col>
            <a-col :span="4">
              <a-button @click="sendMessage" :disabled="lock" type="primary"
                >发送</a-button
              >
            </a-col>
          </a-row>
        </a-card>
      </a-col>
      
      <a-col :xs="24" :sm="24" :md="12" :lg="14" :xl="16">
        <a-card class="card info-card">
          <template #title>
            <label style="font-size: 25px">📚 旅游推荐信息</label>
          </template>
          <div class="info-content">
            <a-empty 
              v-if="!hasRecommendation"
              description="暂无推荐信息，请向助手提问获取旅游推荐"
            >
              <template #image>
                <div style="font-size: 48px">✈️</div>
              </template>
            </a-empty>
            
            <div v-else class="recommendation-info">
              <a-alert
                message="温馨提示"
                description="助手正在为您提供专业的旅游推荐和建议，请在左侧对话框查看详细信息。"
                type="info"
                show-icon
                style="margin-bottom: 20px;"
              />
              
              <a-card title="快速提问建议" size="small">
                <a-space direction="vertical" style="width: 100%;">
                  <a-tag color="blue" style="cursor: pointer" @click="quickAsk('北京有什么好玩的地方？')">
                    🏛️ 北京有什么好玩的地方？
                  </a-tag>
                  <a-tag color="green" style="cursor: pointer" @click="quickAsk('推荐上海的美食')">
                    🍜 推荐上海的美食
                  </a-tag>
                  <a-tag color="purple" style="cursor: pointer" @click="quickAsk('成都三日游路线')">
                    🗺️ 成都三日游路线
                  </a-tag>
                  <a-tag color="orange" style="cursor: pointer" @click="quickAsk('西安有哪些历史景点？')">
                    🏯 西安有哪些历史景点？
                  </a-tag>
                </a-space>
              </a-card>
            </div>
          </div>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from "vue";
import MessageList from "@/views/MessageList.vue";
import type { MessageItem } from "@/types/message";
import { message } from "ant-design-vue";

const messageInfo: { cur: MessageItem | null; list: MessageItem[] } = reactive({
  cur: null,
  list: [
    {
      role: "assistant",
      content: "您好！我是您的旅游推荐助手 🌍\n\n我可以为您提供：\n- 🏖️ 旅游景点推荐\n- 🍴 当地美食建议\n- 🗺️ 旅游路线规划\n- 🏨 住宿交通信息\n- 📅 最佳旅游时间\n\n请告诉我您想去哪里旅游，或者想了解什么信息！",
    },
  ],
});

const question = ref("");
let scrollItem: any = null;
const lock = ref(false);
const hasRecommendation = ref(false);

// 生成唯一的会话ID
const chatId = `chat-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

function scrollBottom() {
  scrollItem?.scrollIntoView({ behavior: "smooth", block: "end" });
}

function addMessage(role: "user" | "assistant", content: string) {
  const cur = {
    role,
    content,
  };
  messageInfo.cur = cur;
  messageInfo.list.push(cur);
  nextTick(() => {
    scrollBottom();
  });
}

function appendMessage(content: string) {
  if (messageInfo.cur) {
    messageInfo.cur.content += content;
  }
  scrollBottom();
}

function sendMessage() {
  if (lock.value) {
    message.warn("助手正在生成回复，请耐心等候");
    return;
  }
  
  if (!question.value.trim()) {
    message.warn("请输入您的问题");
    return;
  }

  const userMessage = question.value;
  addMessage("user", userMessage);
  question.value = "";
  hasRecommendation.value = true;

  const eventSource = new EventSource(
    `/api/assistant/chat?chatId=${chatId}&userMessage=${encodeURIComponent(userMessage)}`,
  );
  
  eventSource.onopen = function (event) {
    addMessage("assistant", "");
  };
  
  eventSource.onmessage = function (event) {
    lock.value = true;
    appendMessage(event.data);
  };
  
  eventSource.onerror = function () {
    eventSource.close();
    lock.value = false;
  };
}

function quickAsk(text: string) {
  question.value = text;
  sendMessage();
}

function clearChat() {
  messageInfo.list = [
    {
      role: "assistant",
      content: "对话已清空，请问有什么可以帮您的？",
    },
  ];
  messageInfo.cur = null;
  hasRecommendation.value = false;
}

onMounted(() => {
  scrollItem = document.getElementById("chat-body-id");
});
</script>

<style lang="less" scoped>
.container {
  height: 100vh;
  max-height: 100vh;
  overflow: auto;
  padding: 10px;

  .row {
    height: 100%;
  }

  .card {
    height: calc(100vh - 20px);
  }

  .chat {
    :deep(.ant-card-body) {
      height: calc(100vh - 110px);
      display: flex;
      flex-direction: column;
      padding: 10px;

      .chat-body {
        border: none;
        height: calc(100% - 60px);
        overflow: auto;
        background: #f4f5f7;
      }
    }
  }

  .info-card {
    :deep(.ant-card-body) {
      height: calc(100vh - 110px);
      overflow: auto;
      padding: 20px;
    }
  }

  .flex-grow {
    flex-grow: 1;
  }

  .footer {
    width: 100%;
    margin-top: 10px;
  }

  .info-content {
    .recommendation-info {
      animation: fadeIn 0.5s;
    }
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

// 响应式样式
@media (max-width: 768px) {
  .container {
    padding: 5px;
    
    .card {
      height: auto;
      min-height: 400px;
      margin-bottom: 10px;
    }
  }
}
</style>

