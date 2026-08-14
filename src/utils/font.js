// font-awesome 字体本地化（Vite 构建时打包，去除第三方 CDN 依赖）
import fontAwesomeTtf from 'font-awesome/fonts/fontawesome-webfont.ttf'

let utilFunc = {
    initIconFont () {
        // 避免重复注入
        if (document.getElementById('fontawesome-webfont')) {
            return;
        }
        var style = document.createElement('style');
        style.id = 'fontawesome-webfont';
        style.textContent = '@font-face { font-family: "fontawesome"; src: url("' + fontAwesomeTtf + '"); }';
        document.head.appendChild(style);
    }
};

export default utilFunc;
