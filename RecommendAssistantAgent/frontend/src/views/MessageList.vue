<script setup lang="ts">
import Message from "@/views/Message.vue";
import type { MessageItem } from "@/types/message";

const messageList = defineProps<{ list: MessageItem[] }>();
</script>

<template>
  <div>
    <template v-for="msg in messageList.list" :key="msg">
      <Message :role="msg.role" :content="msg.content"></Message>
    </template>
  </div>
</template>

<style scoped lang="less"></style>

