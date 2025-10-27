<script setup lang="ts">
import zhCN from "ant-design-vue/es/locale/zh_CN";
import Home from "@/views/Home.vue";

const PRIMARY_COLOR = "#17b392";
</script>

<template>
  <a-config-provider
    :locale="zhCN"
    :theme="{
      token: {
        colorPrimary: PRIMARY_COLOR,
      },
    }"
  >
    <Home></Home>
  </a-config-provider>
</template>

<style lang="less"></style>

