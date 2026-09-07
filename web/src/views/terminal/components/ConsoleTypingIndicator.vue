<template>
  <div class="typing" aria-label="执行中">
    <span></span><span></span><span></span>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.typing {
  display: inline-flex;
  gap: 4px;
  padding: 4px 2px;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6c6c8a;
  animation: typing-blink 1.2s infinite ease-in-out;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes typing-blink {
  0%,
  60%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
</style>
