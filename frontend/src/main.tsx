import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource-variable/martian-mono/standard.css';
import './styles.css';
import { App } from './App';

// Vite가 index.html의 root 요소에 React 화면을 연결해.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
