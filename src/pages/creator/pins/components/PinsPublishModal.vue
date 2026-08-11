<template>
  <div class="modal-overlay" @click="handleOverlayClick">
    <div class="publish-modal" @click.stop>
      <div class="modal-header">
        <span class="modal-title">发布沸点</span>
        <button class="modal-close" @click="$emit('close')">&#xf00d;</button>
      </div>
      <div class="modal-body">
        <PinsPublishBox
          ref="publishBox"
          v-model="content"
          :selectedCircle="selectedCircle"
          :selectedTopic="selectedTopic"
          :publishing="publishing"
          @select-circle="$emit('select-circle')"
          @select-topic="$emit('select-topic')"
          @publish="handlePublish"
          @update:selectedTopic="val => $emit('update:selectedTopic', val)"
        />
      </div>
    </div>
  </div>
</template>

<script>
import PinsPublishBox from '@/pages/pins/components/PinsPublishBox.vue'

export default {
  name: 'PinsPublishModal',
  components: { PinsPublishBox },
  props: {
    value: { type: String, default: '' },
    selectedCircle: { type: Object, default: null },
    selectedTopic: { type: Object, default: null },
    publishing: { type: Boolean, default: false }
  },
  computed: {
    content: {
      get() { return this.value },
      set(val) { this.$emit('input', val) }
    }
  },
  methods: {
    handleOverlayClick() {
      this.$emit('close')
    },
    handlePublish(data) {
      this.$emit('publish', data)
    },
    // 供父组件调用的重置方法
    reset() {
      this.$refs.publishBox && this.$refs.publishBox.reset()
    }
  }
}
</script>

<style lang="less" scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  z-index: 1000;
  padding-top: 80px;
}

.publish-modal {
  background: #fff;
  border-radius: 8px;
  width: 600px;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f2f3f5;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
}

.modal-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  font-family: fontawesome;
  font-size: 16px;
  color: #8a919f;
  cursor: pointer;
  border-radius: 50%;
  &:hover {
    background: #f2f3f5;
    color: #515767;
  }
}

.modal-body {
  padding: 16px 20px;
}
</style>