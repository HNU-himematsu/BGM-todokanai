// 品牌主色唯一定义点：组件库主题（App.vue）与 CSS 主色阶元（styles.css 的 --accent 系列令牌）均由此常量驱动。
// 调整全站色调只需修改此值；主色与白色文字的对比度需不低于 4.5:1，各阶元的派生配方见 styles.css 的 :root。
export const ACCENT = 'rgb(188, 74, 216)'
