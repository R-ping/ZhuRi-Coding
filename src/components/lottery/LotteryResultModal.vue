<template>
    <div class="result-modal-mask" v-if="visible" @click.self="handleClose">
        <div class="result-modal" :class="{ 'batch-result': results.length > 1 }">
            <div class="modal-close" @click="handleClose">&#10005;</div>

            <template v-if="results.length === 1">
                <div class="single-result">
                    <div class="result-title">
                        <span class="title-icon">&#xf058;</span>
                        恭喜抽中
                    </div>
                    <div class="prize-display">
                        <div class="prize-icon" :style="{ background: results[0].color || '#fa8c16' }">
                            {{ results[0].icon || '&#xf06d;' }}
                        </div>
                        <div class="prize-name">{{ results[0].name }}</div>
                        <div class="prize-amount" v-if="results[0].oreAmount">
                            +{{ results[0].oreAmount }} 矿石
                        </div>
                    </div>
                    <div class="result-hint" v-if="results[0].type === 'physical'">
                        实物奖品！请前往"我的收获"填写收货地址
                    </div>
                    <div class="result-hint" v-else-if="results[0].type === 'virtual'">
                        虚拟道具已发放至账户
                    </div>
                    <div class="result-hint" v-else>
                        矿石已发放至账户
                    </div>
                </div>
            </template>

            <template v-else>
                <div class="batch-result-title">
                    <span class="title-icon">&#xf024;</span>
                    十连抽奖励
                </div>
                <div class="batch-prizes">
                    <div
                        v-for="(item, index) in results"
                        :key="index"
                        class="batch-prize-item"
                    >
                        <div class="batch-prize-icon" :style="{ background: item.color || '#fa8c16' }">
                            {{ item.icon || '&#xf06d;' }}
                        </div>
                        <div class="batch-prize-info">
                            <div class="batch-prize-name">{{ item.name }}</div>
                            <div class="batch-prize-amount" v-if="item.oreAmount">+{{ item.oreAmount }} 矿石</div>
                            <div class="batch-prize-tag" v-if="item.type === 'physical'">实物</div>
                        </div>
                    </div>
                </div>
            </template>

            <div class="result-actions">
                <button class="btn btn-primary" @click="handleClose">收下奖励</button>
                <button class="btn btn-secondary" @click="handleDrawAgain" v-if="!isFree && canDraw">
                    再抽一次 ({{ drawCost }} 矿石)
                </button>
                <button class="btn btn-harvest" @click="handleGoHarvest" v-if="hasPhysical">
                    去查看我的收获
                </button>
            </div>
        </div>
    </div>
</template>

<script>
export default {
    name: 'LotteryResultModal',
    props: {
        visible: { type: Boolean, default: false },
        results: { type: Array, default: () => [] },
        isFree: { type: Boolean, default: false },
        canDraw: { type: Boolean, default: true },
        drawCost: { type: Number, default: 200 }
    },
    computed: {
        hasPhysical() {
            return this.results.some(r => r.type === 'physical')
        }
    },
    methods: {
        handleClose() {
            this.$emit('close')
        },
        handleDrawAgain() {
            this.$emit('draw-again')
        },
        handleGoHarvest() {
            this.$emit('go-harvest')
        }
    }
}
</script>

<style lang="less" scoped>
.result-modal-mask {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 9999;
    animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
}

.result-modal {
    width: 340px;
    background: linear-gradient(180deg, #fff 0%, #fff7e6 100%);
    border-radius: 20px;
    padding: 32px 28px 24px;
    position: relative;
    animation: slideUp 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275);
    text-align: center;

    &.batch-result {
        width: 420px;
    }
}

@keyframes slideUp {
    from { transform: translateY(30px) scale(0.9); opacity: 0; }
    to { transform: translateY(0) scale(1); opacity: 1; }
}

.modal-close {
    position: absolute;
    top: 16px;
    right: 20px;
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: #999;
    font-size: 18px;
    border-radius: 50%;
    transition: all 0.2s;

    &:hover {
        background: #f5f5f5;
        color: #333;
    }
}

.result-title {
    font-size: 20px;
    font-weight: 700;
    color: #fa8c16;
    margin-bottom: 24px;
}

.title-icon {
    font-family: fontawesome;
    margin-right: 8px;
}

.prize-display {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-bottom: 20px;
}

.prize-icon {
    width: 80px;
    height: 80px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 32px;
    color: #fff;
    margin-bottom: 12px;
    box-shadow: 0 8px 24px rgba(250, 140, 22, 0.3);
    animation: prizeBounce 0.6s ease;
}

@keyframes prizeBounce {
    0% { transform: scale(0.5); }
    50% { transform: scale(1.1); }
    100% { transform: scale(1); }
}

.prize-name {
    font-size: 18px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 6px;
}

.prize-amount {
    font-size: 22px;
    font-weight: 700;
    color: #fa8c16;
}

.result-hint {
    font-size: 13px;
    color: #666;
    margin-bottom: 20px;
    padding: 8px 16px;
    background: #fff7e6;
    border-radius: 8px;
}

.batch-result-title {
    font-size: 20px;
    font-weight: 700;
    color: #fa8c16;
    margin-bottom: 20px;
}

.batch-prizes {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 12px;
    margin-bottom: 24px;
    max-height: 260px;
    overflow-y: auto;
}

.batch-prize-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
}

.batch-prize-icon {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 20px;
    color: #fff;
}

.batch-prize-info {
    text-align: center;
}

.batch-prize-name {
    font-size: 11px;
    color: #333;
    line-height: 1.4;
    max-width: 60px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.batch-prize-amount {
    font-size: 11px;
    color: #fa8c16;
    font-weight: 600;
}

.batch-prize-tag {
    font-size: 10px;
    color: #ff4d4f;
    background: #fff1f0;
    padding: 1px 6px;
    border-radius: 8px;
    margin-top: 2px;
}

.result-actions {
    display: flex;
    gap: 12px;
}

.btn {
    flex: 1;
    height: 44px;
    border-radius: 22px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    border: none;
    transition: all 0.2s;
}

.btn-primary {
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    color: #fff;

    &:hover {
        opacity: 0.9;
        transform: translateY(-1px);
    }
}

.btn-secondary {
    background: #f5f7fa;
    color: #666;

    &:hover {
        background: #e8e8e8;
    }
}

.btn-harvest {
    background: linear-gradient(135deg, #2f54eb, #597ef7);
    color: #fff;

    &:hover {
        opacity: 0.9;
        transform: translateY(-1px);
    }
}
</style>
