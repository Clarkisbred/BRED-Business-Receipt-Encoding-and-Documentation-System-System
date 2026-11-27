import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { AuthProvider } from './auth/AuthProvider';
import './styles.css';
import { BredThemeProvider } from './theme/BredTheme';
import { Toaster } from 'sonner';

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('The BRED web root element is missing.');
}

createRoot(rootElement).render(
  <StrictMode>
    <BredThemeProvider>
      <AuthProvider>
        <App />
        <Toaster richColors position="bottom-right" />
      </AuthProvider>
    </BredThemeProvider>
  </StrictMode>,
);
